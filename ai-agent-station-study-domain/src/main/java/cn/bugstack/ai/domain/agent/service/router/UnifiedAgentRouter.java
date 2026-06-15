package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.ai.domain.agent.service.security.OutputFilter;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
 * 统一路由：选一个启用的 agent，并判断该 agent 当前可能缺失的工具能力（一句中文描述），
 * 供后端做 PgVector 语义相似度匹配、运行时动态补挂工具。
 */
@Slf4j
@Service
public class UnifiedAgentRouter {

    private static final String ROUTE_PROMPT_TEMPLATE = """
            你是智能体路由器。请根据用户问题，从候选智能体中选择最匹配的一个；并判断：被选中的智能体当前能力是否足以完成该问题，如果可能缺少某类外部工具能力，用一句中文描述这个"可能缺失的工具能力"。

            ## 执行模式说明
            - [Fixed] 单轮问答：适合闲聊、知识问答、代码解释、翻译、总结、文案撰写等一次即可完成的任务。
            - [Auto] 多步自动执行：适合需要分析、执行、监督、修正循环的任务。
            - [Flow] 流程编排：适合需要先调研/规划，再分步骤执行，最后整合交付的复杂任务。

            ## 候选智能体（每行括号 [] 内是 agent_id，紧跟执行模式，其后是它已具备的能力/工具范围）
            %s
            > 注：以上**所有**智能体均已内置"对话记忆与历史回顾"能力（由系统记忆层统一提供，无需额外工具），会自动记住并能回顾用户在本会话及历史对话中提到的信息与之前讨论过的内容。

            ## 用户问题
            %s

            ## 判断原则
            1. 先按智能体描述匹配用户意图，选出最合适的 agent_id。
            2. 任务复杂、需要分步或工具协同时优先 Auto / Flow；简单问答、解释、翻译、总结优先 Fixed。
            3. 再看被选中智能体"已具备的能力"是否覆盖用户问题所需；若用户问题明显需要某类外部能力（如联网搜索、网页抓取、地图路线、天气、股票行情、论文检索、图片生成等），而该智能体描述里没有体现，就把这类"缺失的工具能力"写进 missing_tool_descs。
            4. 如果智能体现有能力已足够，或这是纯知识/闲聊/翻译类问题，missing_tool_descs 留空数组 []。
            5. missing_tool_descs 是一个**数组**，每个元素是一句**自然、完整**的中文，描述<b>一类</b>缺失的工具能力——说清"对什么对象做什么"，
               像在描述一个通用工具的用途（而不是复述用户这一次的具体请求）。后端用每条描述各自做**语义相似度**匹配、再并集补工具，
               所以语义完整、读起来像句话最重要，不必精简成关键词。
               - 若用户问题需要**多类不同能力**（如既要联网搜索、又要查天气），就写**多条**，每类能力一条，互相独立；只缺一类就只写一条。
               - 把用户问题里的**一次性具体名词归纳成它所属的类别**（杭州西湖→旅游景点、某本书→书籍、北京→城市、明天→近期），
                 保留这个类别词（它帮语义匹配到对口工具），只丢掉无助于判断工具类型的一次性细节；
               - 每条围绕"能力/动作 + 对象领域"展开，可以自然成句。
               正例（单条）：["联网搜索网络上的实时资讯、新闻和攻略等信息"]；
               正例（多条）：["联网搜索网络上的实时资讯信息", "查询某个城市的天气预报"]；
               反例（贴死具体请求，别这样）：["搜索杭州西湖的景点攻略"]；
               反例（光剩动词、丢了对象，也别这样）：["搜索"]。
               不要写具体工具名，也不要编造。

            ## 输出要求
            只输出一段严格 JSON，不要 Markdown，不要解释：
            {
              "agent_id": "候选智能体中的 agent_id",
              "missing_tool_descs": ["一句中文描述一类缺失的工具能力", "..."],
              "confidence": 0.0到1.0之间的小数
            }
            """;

    private static final String MISSING_TOOL_PROMPT_TEMPLATE = """
            用户已经指定了下面这个智能体，请只判断：完成用户问题是否还需要该智能体当前不具备的某类外部工具能力。

            ## 已选定的智能体能力
            %s
            > 注：该智能体已内置"对话记忆与历史回顾"能力（由系统记忆层统一提供，无需额外工具），会自动记住并能回顾用户在本会话及历史对话中提到的信息与之前讨论过的内容。

            ## 用户问题
            %s

            ## 要求
            - 若用户问题需要某类该智能体描述里没有体现的外部工具能力（如联网搜索、网页抓取、地图路线、天气、股票行情、论文检索、图片生成等），用一句**自然、完整**的中文描述它——说清"对什么对象做什么"，像在描述一个通用工具的用途。后端用每条描述各自做**语义相似度**匹配，语义完整最重要、不必精简成关键词。
            - missing_tool_descs 是**数组**：需要**多类不同能力**就写多条（每类一条、互相独立），只缺一类写一条，现有能力已足够/纯知识闲聊翻译则留空数组 []。
            - 把用户问题里的**一次性具体名词归纳成所属类别**（杭州西湖→旅游景点、某本书→书籍、北京→城市、明天→近期），保留类别词（帮语义匹配），只丢一次性细节；每条围绕"能力/动作 + 对象领域"自然成句。
              正例（多条）：["联网搜索网络上的实时资讯信息", "查询某个城市的天气预报"]；
              反例（贴死具体请求）：["搜索杭州西湖的景点攻略"]；反例（光剩动词丢了对象）：["搜索"]。不要写具体工具名，也不要编造。

            只输出一段严格 JSON，不要 Markdown，不要解释：
            {"missing_tool_descs": ["一句中文描述一类缺失的工具能力", "..."]}
            """;

    /** 一次 query 最多接受几条 need（防模型吐过多；每条各取 top-k 再并集，下游还有总量上限）。 */
    private static final int MAX_NEEDS = 5;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    @Resource
    private LlmObservationRecorder llmObservationRecorder;

    @Value("${agent.intent-router.client-id:router-small}")
    private String routerClientId;

    public String route(String query) {
        RouteDecision decision = routeDecision(query);
        return decision != null ? decision.agentId() : null;
    }

    public RouteDecision routeDecision(String query) {
        if (query == null || query.isBlank()) return null;

        List<AiAgentVO> agents = repository.queryAvailableAgents();
        if (agents.isEmpty()) {
            log.warn("[UnifiedRouter] no enabled agents");
            return null;
        }

        String prompt = String.format(ROUTE_PROMPT_TEMPLATE, buildAgentList(agents), query);
        String result = callRouterLlm("unified_router", prompt);
        if (result == null || result.isBlank()) {
            return null;
        }

        RouteDecision decision = parseRouteDecision(result, agents);
        if (decision != null && decision.agentId() != null) {
            log.info("[UnifiedRouter] route hit query='{}' -> agent='{}' missingTools={} confidence={}",
                    query, decision.agentId(), decision.missingToolDescs(), decision.confidence());
            return decision;
        }

        log.warn("[UnifiedRouter] invalid route decision: {}", result);
        return RouteDecision.empty(result);
    }

    /**
     * M1：前端已指定 agent（不走路由）时，单独推断该 agent 可能缺失的工具能力（可多条）。
     * 复用 router-small，便宜；没有缺失返回空列表。
     */
    public List<String> inferMissingTool(String agentId, String query) {
        if (agentId == null || agentId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        AiAgentVO agent = repository.queryAiAgentByAgentId(agentId);
        if (agent == null) {
            return List.of();
        }
        String desc = agent.getDescription() != null && !agent.getDescription().isBlank()
                ? agent.getDescription() : "通用助手";
        String prompt = String.format(MISSING_TOOL_PROMPT_TEMPLATE, desc, query);
        String result = callRouterLlm("missing_tool_infer", prompt);
        if (result == null || result.isBlank()) {
            return List.of();
        }
        List<String> missing = parseMissingToolDescs(result);
        log.info("[UnifiedRouter] inferMissingTool agentId={} missingTools={}", agentId, missing);
        return missing;
    }

    /** 调 router LLM，带 0/300/800ms 退避重试 + 观测记录。失败返回 null。 */
    private String callRouterLlm(String stepName, String prompt) {
        ChatClient client;
        try {
            client = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName(routerClientId), ChatClient.class);
        } catch (Exception e) {
            log.warn("[UnifiedRouter] router client '{}' not found: {}", routerClientId, e.getMessage());
            return null;
        }

        String result = null;
        ChatResponse chatResponse = null;
        Exception lastEx = null;
        long[] backoffMs = {0L, 300L, 800L};
        long llmStart = System.currentTimeMillis();
        for (int attempt = 0; attempt < backoffMs.length; attempt++) {
            if (backoffMs[attempt] > 0) {
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[UnifiedRouter] {} retry interrupted at attempt {}", stepName, attempt);
                    return null;
                }
            }
            try {
                chatResponse = client.prompt().user(prompt).call().chatResponse();
                if (chatResponse != null && chatResponse.getResult() != null
                        && chatResponse.getResult().getOutput() != null) {
                    result = chatResponse.getResult().getOutput().getText();
                }
                if (attempt > 0) {
                    log.info("[UnifiedRouter] {} LLM call succeeded on attempt {}/3", stepName, attempt + 1);
                }
                break;
            } catch (Exception e) {
                lastEx = e;
                log.warn("[UnifiedRouter] {} LLM call attempt {}/3 failed: {}", stepName, attempt + 1, e.getMessage());
            }
        }
        long latency = System.currentTimeMillis() - llmStart;
        recordRouterCall(stepName, prompt, result, chatResponse, latency);

        if (result == null || result.isBlank()) {
            log.warn("[UnifiedRouter] {} LLM call failed or empty after 3 attempts, lastError={}",
                    stepName, lastEx == null ? "null" : lastEx.getMessage());
        }
        return result;
    }

    private String buildAgentList(List<AiAgentVO> agents) {
        StringBuilder agentList = new StringBuilder();
        for (int i = 0; i < agents.size(); i++) {
            AiAgentVO agent = agents.get(i);
            String desc = agent.getDescription() != null && !agent.getDescription().isBlank()
                    ? agent.getDescription() : "通用助手";
            String mode = switch (agent.getStrategy()) {
                case "fixedAgentExecuteStrategy" -> "Fixed";
                case "autoAgentExecuteStrategy" -> "Auto";
                case "flowAgentExecuteStrategy" -> "Flow";
                default -> agent.getStrategy();
            };
            agentList.append(String.format("%d. [%s] [%s] %s%n",
                    i + 1, agent.getAgentId(), mode, desc));
        }
        return agentList.toString();
    }

    private RouteDecision parseRouteDecision(String raw, List<AiAgentVO> agents) {
        String cleaned = OutputFilter.stripThinkTags(raw).trim();
        String json = extractJsonObject(cleaned);
        if (json != null) {
            try {
                JSONObject obj = JSON.parseObject(json);
                String agentId = obj.getString("agent_id");
                if (isValidAgent(agentId, agents)) {
                    return new RouteDecision(
                            agentId,
                            extractMissingDescs(obj),
                            obj.getDouble("confidence"),
                            raw
                    );
                }
            } catch (Exception e) {
                log.warn("[UnifiedRouter] route JSON parse failed: {}", e.getMessage());
            }
        }

        String fallbackAgentId = extractAgentIdFallback(cleaned, agents);
        if (fallbackAgentId != null) {
            return new RouteDecision(fallbackAgentId, List.of(), null, raw);
        }
        return null;
    }

    private List<String> parseMissingToolDescs(String raw) {
        String cleaned = OutputFilter.stripThinkTags(raw).trim();
        String json = extractJsonObject(cleaned);
        if (json == null) {
            return List.of();
        }
        try {
            return extractMissingDescs(JSON.parseObject(json));
        } catch (Exception e) {
            log.warn("[UnifiedRouter] missing_tool JSON parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从路由 JSON 取 need 列表：优先读数组 {@code missing_tool_descs}；兼容老的单串 {@code missing_tool_desc}。
     * 每条 sanitize（空/none/null/无 → 丢弃）、按出现序去重、最多保留 {@link #MAX_NEEDS} 条。
     */
    private List<String> extractMissingDescs(JSONObject obj) {
        java.util.LinkedHashSet<String> needs = new java.util.LinkedHashSet<>();
        JSONArray arr = null;
        try {
            arr = obj.getJSONArray("missing_tool_descs");
        } catch (Exception ignored) {
            // 字段不是数组（模型吐成单串）时下面的兼容分支兜底
        }
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                String v = sanitizeMissing(arr.getString(i));
                if (v != null) needs.add(v);
            }
        }
        String single = sanitizeMissing(obj.getString("missing_tool_desc"));
        if (single != null) needs.add(single);
        return needs.stream().limit(MAX_NEEDS).toList();
    }

    private String extractJsonObject(String text) {
        if (text == null) return null;
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return null;
    }

    /** 把空 / none / null / 无 视作"无缺失"，返回 null；否则裁剪到 200 字。 */
    private String sanitizeMissing(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || "none".equalsIgnoreCase(v) || "null".equalsIgnoreCase(v) || "无".equals(v)) {
            return null;
        }
        return v.length() > 200 ? v.substring(0, 200) : v;
    }

    private String extractAgentIdFallback(String result, List<AiAgentVO> agents) {
        String cleaned = result.replaceAll("[`\\n\\r]", "").trim();
        for (AiAgentVO agent : agents) {
            if (agent.getAgentId().equals(cleaned)) {
                return cleaned;
            }
        }

        int start = cleaned.indexOf('[');
        int end = cleaned.indexOf(']');
        if (start >= 0 && end > start) {
            String extracted = cleaned.substring(start + 1, end).trim();
            for (AiAgentVO agent : agents) {
                if (agent.getAgentId().equals(extracted)) {
                    return extracted;
                }
            }
        }
        return null;
    }

    private boolean isValidAgent(String agentId, List<AiAgentVO> agents) {
        if (agentId == null || agentId.isBlank()) return false;
        for (AiAgentVO agent : agents) {
            if (agentId.equals(agent.getAgentId())) {
                return true;
            }
        }
        return false;
    }

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
