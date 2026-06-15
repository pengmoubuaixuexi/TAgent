package cn.bugstack.ai.domain.agent.service.rag.hybrid;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索服务（混合检索的关键词路径）。
 * <p>
 * 为什么要 BM25：向量检索擅长语义近似，但对精确词（产品编号、专有名词、代码符号）召回偏弱。
 * ES 默认打分函数即 BM25，建索引、写文档、查询一条链路打通后，就得到了纯关键词维度的召回结果。
 * 与 PgVector 的 similaritySearch 并行后，通过 RRF 融合，覆盖"精确匹配 + 语义匹配"双场景。
 */
@Slf4j
@Service
public class BM25SearchService {

    @Resource
    private RestClient restClient;

    @Value("${elasticsearch.rag-index:ai-agent-rag}")
    private String ragIndex;

    /**
     * 启动时幂等建索引。已存在则跳过；缺 ES 不阻塞业务启动。
     */
    @PostConstruct
    public void ensureIndex() {
        try {
            Request head = new Request("HEAD", "/" + ragIndex);
            Response resp = restClient.performRequest(head);
            int code = resp.getStatusLine().getStatusCode();
            if (code == 200) {
                log.info("Elasticsearch index already exists: {}", ragIndex);
                // 历史 ES 索引可能没有 user_id 字段（V016 之前），运行时补一次 mapping
                ensureUserIdMapping();
                return;
            }
        } catch (Exception e) {
            log.warn("Check ES index existence failed, will try create: {}", e.getMessage());
        }

        try {
            Request create = new Request("PUT", "/" + ragIndex);
            // standard 分词器对英文/多语言已足够；中文后续可接 ik-analyzer 再优化
            // 多租户隔离：user_id 为 keyword 字段，参与 bool.filter 等值过滤
            String body = """
                    {
                      "settings": {"number_of_shards": 1, "number_of_replicas": 0},
                      "mappings": {
                        "properties": {
                          "content": {"type": "text", "analyzer": "standard"},
                          "knowledge": {"type": "keyword"},
                          "user_id": {"type": "keyword"},
                          "source_id": {"type": "keyword"}
                        }
                      }
                    }
                    """;
            create.setEntity(new NStringEntity(body, ContentType.APPLICATION_JSON));
            restClient.performRequest(create);
            log.info("Elasticsearch index created: {}", ragIndex);
        } catch (Exception e) {
            log.error("Create ES index failed (ES may be down, BM25 will be disabled at runtime): {}", e.getMessage());
        }
    }

    /**
     * 历史 ES 索引 mapping 缺 user_id 字段时，运行时自动 PUT 一次。
     * ES 7.x 允许给已存在的 keyword 字段做 mapping 增量补全（同名不同类型才会拒绝）。
     */
    private void ensureUserIdMapping() {
        try {
            Request put = new Request("PUT", "/" + ragIndex + "/_mapping");
            put.setEntity(new NStringEntity(
                    "{\"properties\":{\"user_id\":{\"type\":\"keyword\"}}}",
                    ContentType.APPLICATION_JSON));
            restClient.performRequest(put);
        } catch (Exception ignored) {
            // 不 fail：即便 mapping 没建上，filter 也只是召回不到结果而已
        }
    }

    /**
     * 批量写入文档到 ES，用 bulk API 降低网络往返。
     * 与 vectorStore.accept() 同步调用即可保持双库一致性（同一批 Document）。
     */
    public void index(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;

        StringBuilder bulkBody = new StringBuilder();
        for (Document doc : documents) {
            JSONObject meta = new JSONObject();
            JSONObject indexLine = new JSONObject();
            indexLine.put("_index", ragIndex);
            indexLine.put("_id", doc.getId());
            meta.put("index", indexLine);
            bulkBody.append(meta.toJSONString()).append("\n");

            JSONObject payload = new JSONObject();
            payload.put("content", doc.getText());
            Object knowledge = doc.getMetadata().get("knowledge");
            if (knowledge != null) payload.put("knowledge", knowledge);
            // H1 第 59 轮：把上传时的原始文件名/URL 也写入 ES，让 RagEvidenceEmitter
            // 在 BM25 路径回收的 Document 上也能拿到可读 source（否则 RRF 融合时 BM25
            // 先 putIfAbsent 占位，向量库带 source 的 Document 进不去，前端只能显示 UUID）
            Object source = doc.getMetadata().get("source");
            if (source != null) payload.put("source", source);
            // 第 61 轮 Phase 2：把 LLM 生成的 parent title 也写入 ES，
            // 让 BM25 路径回收的 child Document 直接带可读小标题（前端 source 优先级最高字段）
            Object title = doc.getMetadata().get("title");
            if (title != null) payload.put("title", title);
            // 多租户隔离：把上传时记录的 user_id 一并写入 ES，让 BM25 召回也能按用户过滤
            Object userId = doc.getMetadata().get("user_id");
            if (userId != null) payload.put("user_id", userId);
            payload.put("source_id", doc.getId());
            bulkBody.append(payload.toJSONString()).append("\n");
        }

        try {
            Request bulk = new Request("POST", "/_bulk");
            bulk.setEntity(new NStringEntity(bulkBody.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
            bulk.addParameter("refresh", "wait_for");
            Response resp = restClient.performRequest(bulk);
            int code = resp.getStatusLine().getStatusCode();
            if (code >= 300) {
                log.warn("ES bulk index returned non-2xx: {}", code);
            } else {
                log.info("ES bulk indexed {} documents to {}", documents.size(), ragIndex);
            }
        } catch (IOException e) {
            log.error("ES bulk index failed: {}", e.getMessage(), e);
        }
    }

    /**
     * BM25 查询：match query 返回打分结果。ES 默认 similarity=BM25。
     * @param query        用户原始问题
     * @param knowledgeTag 知识库 tag 过滤（与 PgVector 一致）
     * @param topK         召回数量
     * @return 带 _score 的文档（score 通过 metadata.bm25_score 暴露给下游融合）
     */
    public List<Document> search(String query, String knowledgeTag, int topK) {
        return search(query, knowledgeTag, null, topK);
    }

    /**
     * 带额外 metadata 过滤条件的 BM25 检索。
     *
     * @param extraFilters 额外的 keyword filter（如 user_id），key 必须是 ES 索引里的 keyword 字段
     */
    public List<Document> search(String query, String knowledgeTag, Map<String, String> extraFilters, int topK) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        try {
            Request search = new Request("GET", "/" + ragIndex + "/_search");
            JSONObject q = new JSONObject();
            q.put("size", topK);
            JSONObject bool = new JSONObject();
            JSONArray must = new JSONArray();
            JSONObject matchWrap = new JSONObject();
            JSONObject matchBody = new JSONObject();
            matchBody.put("content", query);
            matchWrap.put("match", matchBody);
            must.add(matchWrap);
            bool.put("must", must);

            JSONArray filter = new JSONArray();
            if (knowledgeTag != null && !knowledgeTag.isBlank()) {
                JSONObject termWrap = new JSONObject();
                JSONObject term = new JSONObject();
                term.put("knowledge", knowledgeTag);
                termWrap.put("term", term);
                filter.add(termWrap);
            }
            if (extraFilters != null && !extraFilters.isEmpty()) {
                for (Map.Entry<String, String> e : extraFilters.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    if (e.getValue() == null || e.getValue().isBlank()) continue;
                    JSONObject termWrap = new JSONObject();
                    JSONObject term = new JSONObject();
                    term.put(e.getKey(), e.getValue());
                    termWrap.put("term", term);
                    filter.add(termWrap);
                }
            }
            if (!filter.isEmpty()) bool.put("filter", filter);

            JSONObject queryObj = new JSONObject();
            queryObj.put("bool", bool);
            q.put("query", queryObj);

            search.setEntity(new NStringEntity(q.toJSONString(), ContentType.APPLICATION_JSON));
            Response resp = restClient.performRequest(search);
            HttpEntity entity = resp.getEntity();
            String json = StreamUtils.copyToString(entity.getContent(), StandardCharsets.UTF_8);
            JSONObject parsed = JSON.parseObject(json);
            JSONArray hits = parsed.getJSONObject("hits").getJSONArray("hits");
            if (hits == null || hits.isEmpty()) return Collections.emptyList();
            return hits.stream().map(h -> {
                JSONObject hit = (JSONObject) h;
                JSONObject src = hit.getJSONObject("_source");
                String id = hit.getString("_id");
                double score = hit.getDoubleValue("_score");
                String content = src.getString("content");
                Map<String, Object> md = new HashMap<>();
                if (src.containsKey("knowledge")) md.put("knowledge", src.getString("knowledge"));
                // H1 第 59 轮：把 ES 里的 source 也回填到 Document.metadata，
                // 让 RagEvidenceEmitter 能取到原始文件名（旧索引可能没有，所以 if 判一下）
                if (src.containsKey("source")) md.put("source", src.getString("source"));
                // 第 61 轮 Phase 2：透传 title（LLM 生成的精炼小标题），前端 source 优先级最高
                if (src.containsKey("title")) md.put("title", src.getString("title"));
                md.put("bm25_score", score);
                return new Document(id, content, md);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("BM25 search failed, fallback to empty result: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
