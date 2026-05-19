package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 统一路由器：一次 LLM 调用，根据用户 query 从所有 enabled agent 中选出最匹配的 agent_id。
 * <p>
 * 策略跟着 agent 自动确定（agent 表里的 strategy 字段），不需要先选策略再选 agent。
 * <p>
 * 当前端下拉选择"自动"（aiAgentId 为空）时，由 dispatch 层调用本路由器。
 */
@Slf4j
@Service
public class UnifiedAgentRouter {

    private static final String ROUTE_PROMPT_TEMPLATE = """
            你是智能体路由器。根据用户问题，从下方智能体列表中选择最匹配的一个。

            ## 执行模式说明
            - [Fixed] 单轮对话模式：适合日常闲聊、知识问答、写代码、翻译、总结、文案撰写等一次性即可完成的任务。
            - [Auto] 多步自动执行模式：适合需要多轮循环处理的复杂任务，如自动化操作、工具调用、监控分析等，会自动经历分析→执行→监督→总结的循环。
            - [Flow] 流程编排模式：适合需要按固定流水线依次执行多个步骤的场景，如先调研再规划再分步执行。

            ## 选择原则
            1. 优先根据智能体的【功能描述】匹配用户意图
            2. 功能描述匹配度相同时，评估回答的复杂度：
               - 回答可以一次性输出、无需分步组织 → 选复杂度更低的智能体
               - 回答需要分步推理、多维度对比、或信息点较多 → 选复杂度更高的智能体
            3. 仍然不确定时，默认选复杂度最低的智能体

            ## 路由示例
            假设智能体列表如下：
            1. [001] [Fixed] 出差规划 — 政策咨询、费用估算、简单问答
            2. [002] [Auto] 出差规划 — 多日行程安排、比价预订、行程协调
            3. [003] [Flow] 出差规划 — 多城市联程、团队出行、审批对接全流程

            - "出差补贴多少钱一天" → 001（政策咨询，一次回答即可）
            - "帮我安排下周北京三天出差" → 002（多日行程需要多步规划）
            - "帮我先订机票再订酒店再安排会议" → 003（多阶段流水线）

            ## 可选智能体
            %s

            ## 用户问题
            %s

            最后一行只写 agent_id（方括号里的数字），不要其他文字。
            """;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    /** V035 (2026-05-14)：路由 LLM 调用进 Prometheus / event_log */
    @Resource
    private LlmObservationRecorder llmObservationRecorder;

    /** 路由专用 ChatClient 的 clientId */
    @Value("${agent.intent-router.client-id:router-small}")
    private String routerClientId;

    /**
     * 根据 query 从所有 enabled agent 中选出最匹配的 agent_id。
     *
     * @return 选中的 agent_id，路由失败时返回 null
     */
    public String route(String query) {
        if (query == null || query.isBlank()) return null;

        List<AiAgentVO> agents = repository.queryAvailableAgents();
        if (agents.isEmpty()) {
            log.warn("[UnifiedRouter] 没有可用的 enabled agent");
            return null;
        }

        // 构建 agent 列表
        StringBuilder agentList = new StringBuilder();
        for (int i = 0; i < agents.size(); i++) {
            AiAgentVO a = agents.get(i);
            String desc = a.getDescription() != null && !a.getDescription().isBlank()
                    ? a.getDescription() : "通用助手";
            String mode = switch (a.getStrategy()) {
                case "fixedAgentExecuteStrategy" -> "Fixed";
                case "autoAgentExecuteStrategy" -> "Auto";
                case "flowAgentExecuteStrategy" -> "Flow";
                default -> a.getStrategy();
            };
            agentList.append(String.format("%d. [%s] [%s] %s\n",
                    i + 1, a.getAgentId(), mode, desc));
        }

        String prompt = String.format(ROUTE_PROMPT_TEMPLATE, agentList, query);

        // 获取 router ChatClient
        ChatClient client;
        try {
            client = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName(routerClientId), ChatClient.class);
        } catch (Exception e) {
            log.warn("[UnifiedRouter] router client '{}' not found: {}", routerClientId, e.getMessage());
            return null;
        }

        // LLM 调用：网关瞬时 RST/GOAWAY/timeout 容易让一次调用失败，加 2 次轻量重试。
        // 单次失败成本是一次提问被 FAIL，比 2~3 次重试的延迟代价大得多。
        // 退避策略：300ms / 800ms（短抖动，避免长时间阻塞 SSE 用户体验）。
        // V035：从 .content() 改成 .chatResponse()，可拿到 token usage 做记账
        String result = null;
        ChatResponse chatResponse = null;
        Exception lastEx = null;
        long[] backoffMs = {0L, 300L, 800L};
        long llmStart = System.currentTimeMillis();
        for (int attempt = 0; attempt < backoffMs.length; attempt++) {
            if (backoffMs[attempt] > 0) {
                try { Thread.sleep(backoffMs[attempt]); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[UnifiedRouter] retry interrupted at attempt {}", attempt);
                    return null;
                }
            }
            try {
                chatResponse = client.prompt().user(prompt).call().chatResponse();
                if (chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
                    result = chatResponse.getResult().getOutput().getText();
                }
                if (attempt > 0) {
                    log.info("[UnifiedRouter] LLM call succeeded on attempt {}/3", attempt + 1);
                }
                break;
            } catch (Exception e) {
                lastEx = e;
                log.warn("[UnifiedRouter] LLM call attempt {}/3 failed: {}",
                        attempt + 1, e.getMessage());
            }
        }
        long latency = System.currentTimeMillis() - llmStart;

        // V035: 路由 LLM 调用也进 Prometheus / event_log / ES（即使失败也记录一次 failure）
        recordRouterCall("unified_router", prompt, result, chatResponse, latency);

        if (result == null) {
            log.warn("[UnifiedRouter] LLM call failed after 3 attempts, lastError={}",
                    lastEx == null ? "null" : lastEx.getMessage());
            return null;
        }

        if (result == null || result.isBlank()) return null;

        // 清理：去掉 <think> 标签、markdown 围栏、换行、反引号
        result = cn.bugstack.ai.domain.agent.service.security.OutputFilter.stripThinkTags(result)
                .replaceAll("[`\\n\\r]", "")
                .trim();

        // 验证返回的 agent_id 是否在可用列表中
        for (AiAgentVO a : agents) {
            if (a.getAgentId().equals(result)) {
                log.info("[UnifiedRouter] 路由命中: query='{}' -> agent='{}'", query, result);
                return result;
            }
        }

        // LLM 可能返回了带前缀/后缀的文本，尝试提取方括号里的值
        int start = result.indexOf('[');
        int end = result.indexOf(']');
        if (start >= 0 && end > start) {
            String extracted = result.substring(start + 1, end).trim();
            for (AiAgentVO a : agents) {
                if (a.getAgentId().equals(extracted)) {
                    log.info("[UnifiedRouter] 路由命中(提取): query='{}' -> agent='{}'", query, extracted);
                    return extracted;
                }
            }
        }

        log.warn("[UnifiedRouter] LLM 返回了无效的 agent_id: '{}'", result);
        return null;
    }

    /**
     * V035 (2026-05-14): 路由 LLM 调用的统一记账，把"隐形流量"也送进 Prometheus / event_log / ES。
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
                resultText == null || resultText.isBlank() ? new IllegalStateException("empty router response") : null);
    }
}
