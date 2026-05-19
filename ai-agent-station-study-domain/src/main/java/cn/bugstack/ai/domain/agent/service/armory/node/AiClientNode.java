package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;

import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import cn.bugstack.ai.domain.agent.service.router.ModelTierRegistry;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ai agent 客户端对话对象节点
 *
 * @author TAgent
 * 2025/7/19 09:17
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    /** P0.1.3：装配阶段把每个 ChatClient 的 model tier 写进注册表，运行时按 tier 选模型 */
    @Resource
    private ModelTierRegistry modelTierRegistry;

    /** 2026-05-07：装配阶段把每个 ChatClient 的实际工具列表登记，让 prompt 构造代码能注入真实工具信息 */
    @Resource
    private AgentToolRegistry agentToolRegistry;

    /** P1.5.1：MCP 工具调用 metric 装饰器 */
    @Resource
    private McpToolMetrics mcpToolMetrics;

    @Value("${agent.mcp.return-error-on-failure:true}")
    private boolean returnToolErrorOnFailure;

    @Value("${agent.mcp.github.write-enabled:false}")
    private boolean githubWriteEnabled;

    @Value("${agent.mcp.github.search.max-per-page:10}")
    private int githubSearchMaxPerPage;

    @Value("${agent.mcp.github.search.max-result-chars:20000}")
    private int githubSearchMaxResultChars;

    @Value("${agent.mcp.github.search.compact-result-enabled:false}")
    private boolean githubSearchCompactResultEnabled;

    @Value("${agent.mcp.aisearch.strip-server-llm:true}")
    private boolean aiSearchStripServerLlm;

    @Value("${agent.mcp.tool-call.max-attempts:2}")
    private int mcpToolCallMaxAttempts;

    @Value("${agent.mcp.tool-call.retry-delay-ms:1000}")
    private long mcpToolCallRetryDelayMs;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，客户端{}", JSON.toJSONString(requestParameter));

        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());

        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());
        // 把模型 list 转成 map 方便 O(1) 查 tier；data 阶段存的是 List<AiClientModelVO>
        Map<String, AiClientModelVO> modelByModelId = buildModelMap(dynamicContext);

        for (AiClientVO aiClientVO : aiClientList) {
            // 1. 预设话术
            // 2026-05-07 #1 Prompt Cache 前缀稳定化：promptId 按字典序排序后再拼接，
            // 避免 DB 行序变动导致 system prompt byte 漂移、cache 全失效
            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            List<String> promptIdList = new ArrayList<>(aiClientVO.getPromptIdList());
            java.util.Collections.sort(promptIdList);
            for (String promptId : promptIdList) {
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap.get(promptId);
                defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
            }

            // 2. 对话模型
            OpenAiChatModel chatModel = getBean(aiClientVO.getModelBeanName());

            // 3. MCP 服务（个别 MCP 初始化失败时已被 AiClientToolMcpNode 跳过，这里做容错查找）
            List<McpSyncClient> mcpSyncClients = new ArrayList<>();
            List<String> mcpBeanNameList = aiClientVO.getMcpBeanNameList();
            for (String mcpBeanName : mcpBeanNameList) {
                try {
                    mcpSyncClients.add(getBean(mcpBeanName));
                } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException ex) {
                    log.warn("[AiClientNode] MCP bean {} 缺失，跳过：{}", mcpBeanName, ex.getMessage());
                }
            }

            // 4. advisor 顾问角色
            List<Advisor> advisors = new ArrayList<>();
            // P2.5 14.2 PII 脱敏：已改为按需通过 ai_client_advisor 表配置（type=PiiMask），不再全局硬编码
            List<String> advisorBeanNameList = aiClientVO.getAdvisorBeanNameList();
            for (String advisorBeanName : advisorBeanNameList) {
                advisors.add(getBean(advisorBeanName));
            }

            Advisor[] advisorArray = advisors.toArray(new Advisor[]{});

            // 5. 构建对话客户端
            // P1.5.1：从 MCP provider 拿到原始 ToolCallback 后逐一装饰，metric 才能采到
            // 2026-05-07 #1 Prompt Cache：MCP server 注册顺序不稳定 → 工具按 toolDefinition.name() 排序
            // 排序后工具 schema 在 prompt 中的位置 byte 稳定，OpenAI/Anthropic 能命中前缀 cache
            ToolCallback[] rawTools = new SyncMcpToolCallbackProvider(
                    mcpSyncClients.toArray(new McpSyncClient[]{})).getToolCallbacks();
            List<ToolCallback> sortedRaw = new ArrayList<>(java.util.Arrays.asList(rawTools));
            sortedRaw.sort(java.util.Comparator.comparing(t ->
                    t.getToolDefinition() == null ? "" : t.getToolDefinition().name()));
            List<ToolCallback> meteredToolList = new ArrayList<>(sortedRaw.size());
            for (ToolCallback rawTool : sortedRaw) {
                String toolName = rawTool.getToolDefinition() == null ? "" : rawTool.getToolDefinition().name();
                if (!githubWriteEnabled && MeteredToolCallback.isGithubWriteToolName(toolName)) {
                    log.info("[AiClientNode] skip GitHub write tool={} because agent.mcp.github.write-enabled=false", toolName);
                    continue;
                }
                meteredToolList.add(new MeteredToolCallback(rawTool, mcpToolMetrics,
                        returnToolErrorOnFailure, githubWriteEnabled,
                        githubSearchMaxPerPage, githubSearchMaxResultChars,
                        githubSearchCompactResultEnabled, aiSearchStripServerLlm,
                        mcpToolCallMaxAttempts, mcpToolCallRetryDelayMs));
            }
            // LLM 工具名大小写幻觉不再用 alias 翻倍 prompt 解决，
            // 改由自定义 ToolCallingManager 在 lookup 时做 case-insensitive 匹配（见 AiClientModelNode）。
            ToolCallback[] meteredTools = meteredToolList.toArray(new ToolCallback[]{});

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultToolCallbacks(meteredTools)
                    .defaultAdvisors(advisorArray)
                    .build();

            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, chatClient);

            // 2026-05-07：装配同时登记到 AgentToolRegistry，prompt 构造代码可查
            // client 级别没有 tool_mcp 时，从 ChatModel 的 defaultOptions 继承（model 级别的 MCP 工具）
            ToolCallback[] registryTools = meteredTools;
            if (registryTools.length == 0 && chatModel.getDefaultOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tco) {
                List<ToolCallback> modelTools = tco.getToolCallbacks();
                if (modelTools != null && !modelTools.isEmpty()) {
                    registryTools = modelTools.toArray(new ToolCallback[]{});
                    log.info("[AiClientNode] clientId={} client级无MCP工具，从model继承{}个工具", aiClientVO.getClientId(), registryTools.length);
                }
            }
            agentToolRegistry.register(aiClientVO.getClientId(), registryTools);

            // 把 clientId 跟它的 model tier 关联起来；后续 step 就能 modelTierRegistry.pickByTier(small) 选小模型
            AiClientModelVO modelVO = modelByModelId.get(aiClientVO.getModelId());
            if (modelVO != null) {
                modelTierRegistry.register(aiClientVO.getClientId(), ModelTierEnumVO.fromCode(modelVO.getTier()));
            }
        }

        return router(requestParameter, dynamicContext);
    }

    /** dynamicContext 里 AI_CLIENT_MODEL 存的是 List；转 Map 让 tier 查询变 O(1) */
    @SuppressWarnings("unchecked")
    private Map<String, AiClientModelVO> buildModelMap(DefaultArmoryStrategyFactory.DynamicContext dynamicContext) {
        Object raw = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName());
        Map<String, AiClientModelVO> result = new HashMap<>();
        if (raw instanceof List<?>) {
            for (Object o : (List<?>) raw) {
                if (o instanceof AiClientModelVO m) result.put(m.getModelId(), m);
            }
        }
        return result;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }

}
