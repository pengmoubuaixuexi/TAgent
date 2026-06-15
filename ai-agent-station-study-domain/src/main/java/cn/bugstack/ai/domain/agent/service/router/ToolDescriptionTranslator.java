package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具目录文本增强器：刷新工具目录时，对每条 MCP 工具一次 LLM 调用产出两样东西——
 * <ol>
 *   <li><b>zh</b>：一句自然中文用途描述（落 {@code tool_description_zh}，给路由/UI/向量库用）；</li>
 *   <li><b>intent</b>：doc2query 扩写——"用户会怎么自然地说出对这个工具的需求"的典型需求短句
 *       （落 {@code tool_intent_zh}，连同 zh 一起 embed 进 PgVector，给语义检索用）。</li>
 * </ol>
 *
 * <p><b>为什么要 doc2query</b>：用户需求(need)说的是意图/场景（"搜景点攻略"），工具原始描述说的是能力/机制
 * （"联网搜索引擎返回网页"）——即便用 embedding，需求句和机制句的向量也未必够近。把"典型用户需求"提前写进
 * 工具文档（如给联网搜索工具加上"搜某个主题的资料和攻略、查实时资讯"这类自然需求句），就在<b>离线</b>
 * 把工具文本拉到用户需求所在的语义空间，显著抬高 need↔工具 的 cosine 相似度。这是 IR 里 doc2query 的做法。
 *
 * <p>只在<b>手动刷新目录</b>时发生（不在每次请求路径上），不踩每请求配额。调不动/出错时兜底：
 * zh 回退原文、intent 留空，保证刷新不被增强失败阻断。
 */
@Slf4j
@Service
public class ToolDescriptionTranslator {

    private static final String ENRICH_PROMPT_TEMPLATE = """
            你是工具检索增强助手。下面是若干 MCP 工具（name=工具名，desc=英文或中文说明）。对每一条产出两部分：

            1. zh：一句自然、清楚的中文用途描述（约 20~50 字），说明这个工具"对什么对象、能做什么"。保留专有名词
               （如 GitHub、Elasticsearch、Grafana、arXiv、Strava）。不要逐字直译参数，提炼用途、自然成句即可。
            2. intents：6~10 条"用户在什么情况下、会怎么自然地说出对这个工具的需求"的中文短句，用顿号"、"分隔。
               这些短句会和"用户需求描述"做**语义相似度**匹配（embedding 向量，不是关键词匹配），所以要：
               - 写成**自然的需求/问法/场景**，像真实用户会说的整句话，而不是堆砌孤立关键词；
               - 覆盖这个能力的**不同表达方式和典型使用场景**（同一件事换几种自然说法）；
               - 若是通用能力（不限定具体领域），把常见的几类典型需求都铺上。
               例如一个联网搜索引擎：联网搜索网络上的最新信息、查实时资讯和新闻、上网查不确定的事实、搜某个主题的资料和攻略、查百科知识；
               例如一个天气工具：查某个城市的天气预报、明天会不会下雨要不要带伞、未来几天的气温怎么样、某地现在天气如何；
               例如一个驾车路线工具：规划两地之间的驾车路线、自驾导航怎么走、开车从一地到另一地要多久、出行的行车路线规划。

            输入（JSON 数组，i 为序号，name 为工具名，desc 为说明）：
            %s

            只输出一段严格 JSON 数组，不要 Markdown，不要解释，元素顺序、i 与输入一致：
            [{"i":0,"zh":"中文用途描述","intents":"自然需求短句1、自然需求短句2、自然需求短句3"}, ...]
            """;

    @Resource
    private ApplicationContext applicationContext;

    @Value("${agent.intent-router.client-id:router-small}")
    private String routerClientId;

    @Value("${agent.dynamic-tools.catalog.translate-enabled:true}")
    private boolean translateEnabled;

    @Value("${agent.dynamic-tools.catalog.translate-batch-size:20}")
    private int translateBatchSize;

    /** 一条工具的增强文本：中文用途 + doc2query 意图扩写。 */
    public record ToolText(String zh, String intentZh) {}

    /**
     * 把工具说明增强成 {中文用途, 意图扩写}，顺序与入参对齐。
     *
     * @param toolNames        工具名列表
     * @param rawDescriptions  原始（可能英文）说明列表，长度需与 toolNames 一致
     * @return 每条工具的 {@link ToolText}（zh 已是中文的原样保留、英文的翻译、失败回退原文；intent 失败留空）
     */
    public List<ToolText> toCatalogText(List<String> toolNames, List<String> rawDescriptions) {
        int size = toolNames == null ? 0 : toolNames.size();
        List<ToolText> result = new ArrayList<>(size);
        if (size == 0) {
            return result;
        }

        // 工作态：zh 先用原文占位（LLM 成功就覆盖成干净用途描述），intent 先空。用可变数组承接 LLM 回填。
        String[] zh = new String[size];
        String[] intent = new String[size];
        for (int i = 0; i < size; i++) {
            String raw = rawDescriptions.get(i) == null ? "" : rawDescriptions.get(i).trim();
            zh[i] = raw;
            intent[i] = "";
        }

        if (translateEnabled) {
            // 所有非空工具都要送 LLM：英文的要翻 zh，中文的虽不翻 zh 但也要生成 intent。
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                if (!zh[i].isBlank()) {
                    pending.add(i);
                }
            }
            ChatClient client = pending.isEmpty() ? null : resolveClient();
            if (client == null && !pending.isEmpty()) {
                log.warn("[ToolEnrich] router client '{}' unavailable, keep raw descriptions / empty intents", routerClientId);
            } else {
                int batch = translateBatchSize > 0 ? translateBatchSize : 20;
                for (int from = 0; from < pending.size(); from += batch) {
                    List<Integer> chunk = pending.subList(from, Math.min(from + batch, pending.size()));
                    enrichChunk(client, chunk, toolNames, zh, intent);
                }
            }
        }

        for (int i = 0; i < size; i++) {
            result.add(new ToolText(zh[i], intent[i]));
        }
        return result;
    }

    private void enrichChunk(ChatClient client, List<Integer> chunk, List<String> toolNames,
                             String[] zh, String[] intent) {
        JSONArray input = new JSONArray();
        for (int idx : chunk) {
            JSONObject item = new JSONObject();
            item.put("i", idx);
            item.put("name", safe(toolNames.get(idx)));
            item.put("desc", truncate(cleanDesc(zh[idx]), 400));
            input.add(item);
        }
        String prompt = String.format(ENRICH_PROMPT_TEMPLATE, input.toJSONString());
        try {
            String raw = client.prompt().user(prompt).call().content();
            applyEnrichment(raw, zh, intent);
        } catch (Exception e) {
            log.warn("[ToolEnrich] enrich batch failed (keep raw / empty intent): {}", e.getMessage());
        }
    }

    private void applyEnrichment(String raw, String[] zh, String[] intent) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String cleaned = OutputFilter.stripThinkTags(raw).trim()
                .replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return;
        }
        try {
            JSONArray arr = JSON.parseArray(cleaned.substring(start, end + 1));
            for (int k = 0; k < arr.size(); k++) {
                JSONObject obj = arr.getJSONObject(k);
                Integer i = obj.getInteger("i");
                if (i == null || i < 0 || i >= zh.length) {
                    continue;
                }
                String zhVal = obj.getString("zh");
                String intentVal = obj.getString("intents");
                // zh：一律采用 LLM 的干净用途描述（覆盖原文）——原始 desc 常带一大段参数噪声，
                // LLM 提炼过的更适合 embed 做语义检索字段；LLM 失败时（zhVal 空）才保留原文兜底。
                if (zhVal != null && !zhVal.isBlank()) {
                    zh[i] = zhVal.trim();
                }
                if (intentVal != null && !intentVal.isBlank()) {
                    intent[i] = intentVal.trim();
                }
            }
        } catch (Exception e) {
            log.warn("[ToolEnrich] parse enrichment JSON failed (keep raw): {}", e.getMessage());
        }
    }

    private ChatClient resolveClient() {
        try {
            return applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName(routerClientId), ChatClient.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清掉工具说明里的参数块噪声再喂给 LLM。很多 MCP 工具的 desc 是 Python docstring 风格，正文后面跟着
     * 一大段 "Args:/Parameters:/参数:" 的参数说明（动辄几百字），截断 400 字时正文反被参数噪声挤掉，
     * 模型只能憋出"执行搜索"这种空泛 zh、intent 也跟着空泛（实测 AIsearch 就是这么被带偏的）。
     * 这里只保留参数块之前的用途正文。
     */
    private String cleanDesc(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String v = value;
        for (String marker : new String[]{"\nArgs:", "\n    Args:", "Args:", "\nParameters:", "Parameters:", "\n参数", "参数："}) {
            int p = v.indexOf(marker);
            if (p > 0) {
                v = v.substring(0, p);
            }
        }
        return v.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
