package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用 router-small LLM 从 agent 池中按描述匹配最合适的 agent。
 * <p>
 * 匹配原则：query 意图匹配、领域匹配优先；描述为空时用名称 fallback。
 * 单 agent 直接返回不上 LLM；空池返回 null。
 */
@Slf4j
@Service
public class AgentPoolSelector implements IAgentSelector {

    private static final String SELECT_PROMPT = """
            从下列智能体中选择最适合回答用户问题的那个。只输出 agent_id，不要任何其他文字。

            用户问题：%s

            候选智能体列表：
            %s
            """;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private LlmCallGateway llmCallGateway;

    @Override
    public String select(String query, List<AiAgentVO> pool) {
        if (pool == null || pool.isEmpty()) return null;
        if (pool.size() == 1) {
            log.info("[AgentPool] only 1 agent, auto-select agentId={}", pool.get(0).getAgentId());
            return pool.get(0).getAgentId();
        }

        ChatClient routerSmall;
        try {
            routerSmall = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName("router-small"), ChatClient.class);
        } catch (Exception e) {
            log.warn("[AgentPool] router-small not available, fallback to first agent");
            return pool.get(0).getAgentId();
        }

        String agentList = buildAgentList(pool);
        String prompt = String.format(SELECT_PROMPT, query, agentList);

        try {
            ChatResponse chatResponse = llmCallGateway.call(() -> routerSmall.prompt(prompt).call());
            String resp = chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null
                    ? chatResponse.getResult().getOutput().getText() : null;
            if (resp != null) {
                // 剥离 <think>...</think>（DeepSeek / Claude thinking 模式）
                String t = resp.trim();
                int thinkClose = t.lastIndexOf("</think>");
                if (thinkClose > 0) {
                    t = t.substring(thinkClose + "</think>".length()).trim();
                }
                String candidate = t;
                // 验一下是不是在候选池里
                for (AiAgentVO a : pool) {
                    if (a.getAgentId().equals(candidate)) {
                        log.info("[AgentPool] LLM selected agentId={} agentName={}", a.getAgentId(), a.getAgentName());
                        return candidate;
                    }
                }
                log.warn("[AgentPool] LLM returned unknown agentId={}, fallback to first", candidate);
            }
        } catch (Exception e) {
            log.warn("[AgentPool] LLM call failed, fallback to first: {}", e.getMessage());
        }
        return pool.get(0).getAgentId();
    }

    private String buildAgentList(List<AiAgentVO> pool) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pool.size(); i++) {
            AiAgentVO a = pool.get(i);
            sb.append(i + 1).append(". agent_id: ").append(a.getAgentId())
                    .append("\n   名称: ").append(a.getAgentName())
                    .append("\n   描述: ").append(a.getDescription() != null ? a.getDescription() : "无")
                    .append("\n");
        }
        return sb.toString();
    }
}
