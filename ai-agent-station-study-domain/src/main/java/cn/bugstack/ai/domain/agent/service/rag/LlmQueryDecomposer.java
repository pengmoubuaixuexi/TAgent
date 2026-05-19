package cn.bugstack.ai.domain.agent.service.rag;

import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM-based query decomposer for multi-hop RAG questions.
 */
@Slf4j
public class LlmQueryDecomposer implements IQueryDecomposer {

    private static final String DECOMPOSE_PROMPT = """
            Break the following user query into simpler, independent sub-questions that can be answered separately.
            Return ONLY a JSON array of strings, no explanation or additional text.

            User query: %s

            JSON array:""";

    private final ChatClient chatClient;
    private final int minLengthToDecompose;
    private LlmObservationRecorder llmObservationRecorder;

    public LlmQueryDecomposer(ChatClient chatClient) {
        this(chatClient, 20);
    }

    public LlmQueryDecomposer(ChatClient chatClient, int minLengthToDecompose) {
        this.chatClient = chatClient;
        this.minLengthToDecompose = minLengthToDecompose;
    }

    public void setObservability(LlmObservationRecorder llmObservationRecorder) {
        this.llmObservationRecorder = llmObservationRecorder;
    }

    @Override
    public boolean shouldDecompose(String query) {
        if (query == null || query.isBlank()) return false;
        long questionMarks = query.chars().filter(c -> c == '?' || c == '？').count();
        if (questionMarks > 1) return true;
        if (query.length() < minLengthToDecompose) return false;
        return java.util.regex.Pattern
                .compile("(并且|还想?要?知道|同时|另外|以及|分别|各自)")
                .matcher(query)
                .find();
    }

    @Override
    public List<String> decompose(String query) {
        if (chatClient == null || query == null || query.isBlank()) return Collections.singletonList(query);
        if (!shouldDecompose(query)) return Collections.singletonList(query);

        try {
            String prompt = String.format(DECOMPOSE_PROMPT, query);
            long start = System.currentTimeMillis();
            ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();
            long latency = System.currentTimeMillis() - start;
            String result = chatResponse != null
                    && chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : null;

            recordCall("query_decomposer", prompt, result, chatResponse, latency);
            if (result == null || result.isBlank()) return Collections.singletonList(query);

            int startIdx = result.indexOf('[');
            int endIdx = result.lastIndexOf(']');
            if (startIdx < 0 || endIdx <= startIdx) return Collections.singletonList(query);
            String json = result.substring(startIdx, endIdx + 1);

            @SuppressWarnings("unchecked")
            List<String> list = JSON.parseObject(json, List.class);
            if (list == null || list.isEmpty()) return Collections.singletonList(query);

            List<String> cleaned = list.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
            if (cleaned.isEmpty()) return Collections.singletonList(query);

            log.debug("Query decomposed: '{}' -> {} sub-queries",
                    query.substring(0, Math.min(50, query.length())), cleaned.size());
            return cleaned;
        } catch (Exception e) {
            log.debug("Query decomposition failed: {}", e.getMessage());
            return Collections.singletonList(query);
        }
    }

    private void recordCall(String stepName, String prompt, String resultText, ChatResponse response, long latency) {
        if (llmObservationRecorder == null) return;
        llmObservationRecorder.record(LlmCallContext.builder()
                .stepName(stepName)
                .prompt(prompt)
                .resultText(resultText)
                .sessionId(MDC.get("sessionId"))
                .userId(MDC.get("userId"))
                .tenantId(MDC.get("tenantId"))
                .agentId(MDC.get("agentId"))
                .build(), response, latency,
                resultText == null || resultText.isBlank() ? new IllegalStateException("empty query decomposer response") : null);
    }
}
