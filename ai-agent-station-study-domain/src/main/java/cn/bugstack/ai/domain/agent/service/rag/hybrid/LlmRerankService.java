package cn.bugstack.ai.domain.agent.service.rag.hybrid;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LLM 驱动的 Re-Rank 服务。
 * <p>
 * 为什么用 LLM：BM25 + 向量的召回给出的是"相关可能性"，但相关性的精确判断需要语义理解。
 * 让模型一次性看全部候选和 query，直接输出每条的相关度分数，再按分排序取 top-N。
 * <p>
 * 这里不注入 ChatClient bean：Spring AI 的 ChatClient 是按 clientId 动态注册的（armory 装配体系），
 * 所以由调用方（RagAnswerAdvisor 构造时）把使用的 ChatClient 传入，复用对话所用的同一个客户端。
 */
@Slf4j
@Service
public class LlmRerankService implements IRerankService {

    /** Spring Boot OpenAI starter 默认自动装配一个 ChatModel 主 Bean；缺失时整个 rerank 能力优雅降级 */
    @Autowired(required = false)
    private ChatModel chatModel;

    private volatile ChatClient cachedChatClient;

    private ChatClient chatClient() {
        if (chatModel == null) return null;
        ChatClient local = cachedChatClient;
        if (local == null) {
            synchronized (this) {
                local = cachedChatClient;
                if (local == null) {
                    local = ChatClient.create(chatModel);
                    cachedChatClient = local;
                }
            }
        }
        return local;
    }

    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        ChatClient client = chatClient();
        if (client == null) {
            log.warn("Rerank ChatModel not available, fallback to original order");
            return candidates == null ? List.of() : candidates.subList(0, Math.min(topN, candidates.size()));
        }
        return rerank(client, query, candidates, topN);
    }

    private static final String RERANK_PROMPT = """
            你是检索结果相关性打分器。针对用户查询，对下列候选文档逐条打分（0-10 分，越高越相关）。

            查询：%s

            候选文档（共 %d 条，每条前是编号）：
            %s

            要求：
            1. 仅输出 JSON 数组，格式：[{"index": <编号>, "score": <0-10>}, ...]
            2. 不要解释，不要 markdown 包裹，直接纯 JSON。
            3. 编号必须与候选文档保持一致，所有候选都要打分。
            """;

    public List<Document> rerank(ChatClient chatClient, String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() <= topN) {
            // 候选数已不超过 topN，仍然跑一次 rerank 以保证顺序质量是可以的；
            // 但为了省 token，这种情况直接返回原顺序
            return candidates;
        }

        long start = System.currentTimeMillis();
        String candidateText = buildCandidateBlock(candidates);
        String prompt = String.format(RERANK_PROMPT, query, candidates.size(), candidateText);

        String content;
        try {
            content = chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            log.warn("LLM rerank call failed, fallback to hybrid order: {}", e.getMessage());
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        List<Document> reranked = applyScores(content, candidates, topN);
        long latency = System.currentTimeMillis() - start;

        MDC.put("retrieval", "rerank");
        MDC.put("candidates", String.valueOf(candidates.size()));
        MDC.put("topN", String.valueOf(reranked.size()));
        MDC.put("latencyMs", String.valueOf(latency));
        try {
            log.info("LLM rerank done: candidates={} topN={} latencyMs={}", candidates.size(), reranked.size(), latency);
        } finally {
            MDC.remove("retrieval");
            MDC.remove("candidates");
            MDC.remove("topN");
            MDC.remove("latencyMs");
        }
        return reranked;
    }

    private String buildCandidateBlock(List<Document> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i).getText();
            if (text == null) text = "";
            if (text.length() > 800) text = text.substring(0, 800) + "...";
            sb.append("[").append(i).append("] ").append(text.replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }

    private List<Document> applyScores(String llmOutput, List<Document> candidates, int topN) {
        double[] scores = new double[candidates.size()];
        try {
            String cleaned = stripMarkdownFences(llmOutput);
            JSONArray arr = JSON.parseArray(cleaned);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                int idx = item.getIntValue("index");
                double s = item.getDoubleValue("score");
                if (idx >= 0 && idx < candidates.size()) {
                    scores[idx] = s;
                }
            }
        } catch (Exception e) {
            log.warn("Parse rerank LLM output failed, fallback to hybrid order. output={}", llmOutput);
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) indices.add(i);
        indices.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());

        List<Document> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, indices.size()); i++) {
            out.add(candidates.get(indices.get(i)));
        }
        return out;
    }

    private String stripMarkdownFences(String s) {
        if (s == null) return "[]";
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
