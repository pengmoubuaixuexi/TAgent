package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.RagRouterDecision;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * LLM-based RAG Router（P0.1.4 + P2.3）。
 * <p>
 * 用 router-small 一次调用完成：
 * <ol>
 *   <li>是否需要检索（闲聊/问候 → skip）</li>
 *   <li>推荐检索路径：SIMPLE / HYBRID / DECOMPOSE / FUSION</li>
 *   <li>DECOMPOSE 时直接输出子查询列表</li>
 *   <li>FUSION 时直接输出检索变体列表</li>
 * </ol>
 * 替代原来分散的 HeuristicRagRouter.shouldRetrieve() + LlmQueryDecomposer.decompose()
 * + RagFusionService.generateVariants() 三次调用。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.rag-router.llm-enabled", havingValue = "true")
public class LlmRagRouter implements IRagRouter {

    private static final String ROUTER_PROMPT = """
            Analyze the user query and decide the optimal RAG (Retrieval-Augmented Generation) strategy.

            ## Skip retrieval (shouldRetrieve=false) if the query is:
            - Casual chitchat, greetings, thanks, farewells
            - Personal questions about the AI itself ("what's your name", "who are you")
            - Simple opinions or subjective questions without factual need
            - Follow-ups that don't need new facts ("repeat that", "say it again")

            ## If retrieval IS needed (shouldRetrieve=true), pick ONE strategy:
            - SIMPLE: single straightforward fact lookup ("what is X", "who is Y")
            - HYBRID: general knowledge question, benefits from keyword + semantic search
            - DECOMPOSE: complex multi-hop question that needs sub-questions (e.g. "compare A and B", "how does X relate to Y and what impact did Z have")
            - FUSION: abstract/ambiguous question that benefits from multiple search angles

            ## Rules:
            - For DECOMPOSE: provide 2-4 specific sub-queries in the original language
            - For FUSION: provide 2-3 search variants rephrased from different angles
            - For SIMPLE/HYBRID: subQueries and variants can be empty arrays
            - Default to HYBRID when unsure

            Output ONLY a JSON object (no markdown fences, no explanation):

            {"shouldRetrieve":true,"path":"HYBRID","subQueries":[],"variants":[],"reason":"general knowledge question"}

            User query: %s
            """;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private LlmCallGateway llmCallGateway;

    /** V035 (2026-05-14)：router LLM 调用也进 Prometheus / event_log，对齐 auto/flow */
    @Resource
    private LlmObservationRecorder llmObservationRecorder;

    @Value("${agent.rag-router.min-length-chars:4}")
    private int minLengthChars;

    @Value("${agent.rag-router.llm-enabled:true}")
    private boolean enabled;

    /** 总开关；agent.rag-router.enabled=false 时整套 router（LLM/heuristic）退化为 always retrieve */
    @Value("${agent.rag-router.enabled:true}")
    private boolean routerEnabled;

    /** RAG 路由专用 clientId；默认复用意图路由的小模型 client。 */
    @Value("${agent.rag-router.client-id:${agent.intent-router.client-id:router-small}}")
    private String ragRouterClientId;

    @Override
    public boolean shouldRetrieve(String query) {
        return decide(query).isShouldRetrieve();
    }

    /** 延迟从容器取 RAG router client，因为 RouterPoolConfig 在 ApplicationReadyEvent 才装配 */
    private ChatClient getRouterSmall() {
        try {
            return applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName(ragRouterClientId), ChatClient.class);
        } catch (Exception e) {
            log.debug("rag-router llm client not available: {}", ragRouterClientId);
            return null;
        }
    }

    @Override
    public RagRouterDecision decide(String query) {
        if (!routerEnabled || !enabled || query == null || query.isBlank()) {
            return RagRouterDecision.retrieve();
        }

        // 长度门槛：太短直接用规则跳过，省一次 LLM 调用
        String trimmed = query.trim();
        if (trimmed.length() < minLengthChars) {
            log.debug("rag-router llm skip: too short, len={}", trimmed.length());
            return RagRouterDecision.skip("too short");
        }

        ChatClient routerSmall = getRouterSmall();
        if (routerSmall == null) {
            log.debug("rag-router llm: client {} not available, fallback to retrieve", ragRouterClientId);
            return RagRouterDecision.retrieve();
        }

        try {
            String prompt = String.format(ROUTER_PROMPT, trimmed);
            long start = System.currentTimeMillis();
            ChatResponse chatResponse = llmCallGateway.call(() -> routerSmall.prompt(prompt).call());
            long latency = System.currentTimeMillis() - start;
            String raw = chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null
                    ? chatResponse.getResult().getOutput().getText() : null;

            // V035: 记录路由 LLM 的 token / cost / latency 到 Prometheus + event_log
            recordRouterCall("rag_router", prompt, raw, chatResponse, latency);

            if (raw == null || raw.isBlank()) {
                log.debug("rag-router llm returned empty, fallback to retrieve");
                return RagRouterDecision.retrieve();
            }

            RagRouterDecision decision = parseResponse(raw);
            log.info("rag-router llm decision: retrieve={} path={} subQueries={} variants={} reason={}",
                    decision.isShouldRetrieve(), decision.getPath(),
                    decision.getSubQueries().size(), decision.getVariants().size(),
                    decision.getReason());
            return decision;
        } catch (Exception e) {
            log.warn("rag-router llm failed, fallback to retrieve: {}", e.getMessage());
            return RagRouterDecision.retrieve();
        }
    }

    /**
     * V035: 路由层 LLM 调用的统一记账。
     * 把 router 这种"隐形流量"也送进 Prometheus / ai_event_log，方便看完整成本。
     */
    private void recordRouterCall(String stepName, String prompt, String resultText,
                                  ChatResponse response, long latency) {
        llmObservationRecorder.record(LlmCallContext.builder()
                .stepName(stepName)
                .prompt(prompt)
                .resultText(resultText)
                .sessionId(MDC.get("sessionId"))
                .userId(MDC.get("userId"))
                .tenantId(MDC.get("tenantId"))
                .agentId(MDC.get("agentId"))
                .build(), response, latency,
                resultText == null || resultText.isBlank() ? new IllegalStateException("empty rag router response") : null);
    }

    private RagRouterDecision parseResponse(String raw) {
        String json = stripThinkAndFences(raw);

        JSONObject obj;
        try {
            obj = JSON.parseObject(json);
        } catch (Exception e) {
            log.debug("rag-router JSON parse failed, raw={}", raw.substring(0, Math.min(200, raw.length())));
            return RagRouterDecision.retrieve();
        }

        boolean shouldRetrieve = obj.getBooleanValue("shouldRetrieve");
        String path = obj.getString("path");
        String reason = obj.getString("reason");

        @SuppressWarnings("unchecked")
        List<String> subQueries = obj.getJSONArray("subQueries") != null
                ? obj.getJSONArray("subQueries").toJavaList(String.class)
                : Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<String> variants = obj.getJSONArray("variants") != null
                ? obj.getJSONArray("variants").toJavaList(String.class)
                : Collections.emptyList();

        // 规范化 path
        if (path != null) path = path.toUpperCase().trim();
        if (!RagRouterDecision.PATH_DECOMPOSE.equals(path)
                && !RagRouterDecision.PATH_FUSION.equals(path)
                && !RagRouterDecision.PATH_SIMPLE.equals(path)) {
            path = RagRouterDecision.PATH_HYBRID;
        }

        return RagRouterDecision.builder()
                .shouldRetrieve(shouldRetrieve)
                .path(path)
                .subQueries(subQueries != null ? subQueries : Collections.emptyList())
                .variants(variants != null ? variants : Collections.emptyList())
                .reason(reason)
                .build();
    }

    /** 剥离 &lt;think&gt; 标签和 ```json 围栏 */
    static String stripThinkAndFences(String s) {
        String t = s.trim();
        int thinkClose = t.lastIndexOf("</think>");
        if (thinkClose > 0) {
            t = t.substring(thinkClose + "</think>".length()).trim();
        }
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                t = t.substring(firstNl + 1, lastFence).trim();
            } else if (lastFence > 3) {
                t = t.substring(3, lastFence).trim();
            }
        }
        return t;
    }
}
