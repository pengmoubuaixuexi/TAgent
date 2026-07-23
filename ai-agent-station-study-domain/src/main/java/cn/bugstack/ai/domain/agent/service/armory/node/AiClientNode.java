package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;

import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.EpisodicMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.ReadOnlyChatMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.prompt.ContextEnvelopeRenderAdvisor;
import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ai agent 客户端对话对象节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/19 09:17
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    /** 2026-05-07：装配阶段把每个 ChatClient 的实际工具列表登记，让 prompt 构造代码能注入真实工具信息 */
    @Resource
    private AgentToolRegistry agentToolRegistry;

    /** P1.5.1：MCP 工具调用 metric 装饰器 */
    @Resource
    private McpToolMetrics mcpToolMetrics;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry mcpClientRegistry;

    /** G1-C：人工审批 gate（可选），装配时 setter 注入到 MeteredToolCallback */
    @Resource
    private cn.bugstack.ai.domain.agent.service.security.HumanApprovalGate humanApprovalGate;

    /** H3-A：工具调用进度 SSE emitter，装配时 setter 注入到 MeteredToolCallback */
    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter toolCallProgressEmitter;

    /** P0（Codex #2）工具调用实证台账，装配时 setter 注入到 MeteredToolCallback，供 Step3 质检取证 */
    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolCallLedger toolCallLedger;

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
        List<AiClientModelVO> modelConfigs = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName());
        Map<String, AiClientModelVO> modelByBeanName = new java.util.HashMap<>();
        if (modelConfigs != null) {
            for (AiClientModelVO model : modelConfigs) {
                modelByBeanName.put(
                        AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(model.getModelId()),
                        model);
            }
        }
        List<String> agentMemoryAdvisorBeanNames = collectAgentMemoryAdvisorBeanNames(aiClientList);
        if (!agentMemoryAdvisorBeanNames.isEmpty()) {
            log.info("[AiClientNode] agent memory advisors propagated to all clients: {}", agentMemoryAdvisorBeanNames);
        }

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
            List<ToolCallback> rawToolList = new ArrayList<>();
            List<String> mcpBeanNameList = aiClientVO.getMcpBeanNameList();
            for (String mcpBeanName : mcpBeanNameList) {
                try {
                    McpSyncClient client = getBean(mcpBeanName);
                    String mcpId = extractToolMcpId(mcpBeanName);
                    if (mcpId != null) {
                        ToolCallback[] callbacks = mcpClientRegistry.getToolCallbacksForAssembly(mcpId, client);
                        mcpClientRegistry.registerCallbacks(mcpId, callbacks);
                        rawToolList.addAll(java.util.Arrays.asList(callbacks));
                    } else {
                        mcpSyncClients.add(client);
                    }
                } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException ex) {
                    log.warn("[AiClientNode] MCP bean {} 缺失，跳过：{}", mcpBeanName, ex.getMessage());
                } catch (Exception ex) {
                    log.warn("[AiClientNode] MCP bean {} listTools/reconnect failed during client assembly, skipped: {}",
                            mcpBeanName, ex.toString());
                }
            }

            // 4. advisor 顾问角色
            List<Advisor> advisors = new ArrayList<>();
            // P2.5 14.2 PII 脱敏：已改为按需通过 ai_client_advisor 表配置（type=PiiMask），不再全局硬编码
            List<String> advisorBeanNameList = mergeAdvisorBeanNames(
                    aiClientVO.getAdvisorBeanNameList(), agentMemoryAdvisorBeanNames);
            for (String advisorBeanName : advisorBeanNameList) {
                advisors.add(getBean(advisorBeanName));
            }
            // P2-B-2：LTM/Episodic 只采集 section 到 request.context，统一由 render advisor 渲染单个 envelope。
            // 无 ctx.envelope.* 时 no-op；不需要改 DB advisor 配置。
            advisors.add(new ContextEnvelopeRenderAdvisor());
            AiClientModelVO configuredModel = modelByBeanName.get(aiClientVO.getModelBeanName());
            boolean imageInputSupported = configuredModel != null && configuredModel.supportsImageInput();
            advisors.add(new cn.bugstack.ai.domain.agent.service.multimodal.MultimodalMessageAdvisor(
                    imageInputSupported));
            log.info("[AiClientNode] clientId={} model={} imageInputSupported={}",
                    aiClientVO.getClientId(),
                    configuredModel == null ? aiClientVO.getModelBeanName() : configuredModel.getModelName(),
                    imageInputSupported);

            Advisor[] advisorArray = advisors.toArray(new Advisor[]{});

            // 5. 构建对话客户端
            // P1.5.1：从 MCP provider 拿到原始 ToolCallback 后逐一装饰，metric 才能采到
            // 2026-05-07 #1 Prompt Cache：MCP server 注册顺序不稳定 → 工具按 toolDefinition.name() 排序
            // 排序后工具 schema 在 prompt 中的位置 byte 稳定，OpenAI/Anthropic 能命中前缀 cache
            if (!mcpSyncClients.isEmpty()) {
                ToolCallback[] rawTools = new SyncMcpToolCallbackProvider(
                        mcpSyncClients.toArray(new McpSyncClient[]{})).getToolCallbacks();
                rawToolList.addAll(java.util.Arrays.asList(rawTools));
            }
            List<ToolCallback> sortedRaw = new ArrayList<>(rawToolList);
            sortedRaw.sort(java.util.Comparator.comparing(t ->
                    t.getToolDefinition() == null ? "" : t.getToolDefinition().name()));
            List<ToolCallback> meteredToolList = new ArrayList<>(sortedRaw.size());
            for (ToolCallback rawTool : sortedRaw) {
                String toolName = rawTool.getToolDefinition() == null ? "" : rawTool.getToolDefinition().name();
                if (!githubWriteEnabled && MeteredToolCallback.isGithubWriteToolName(toolName)) {
                    log.info("[AiClientNode] skip GitHub write tool={} because agent.mcp.github.write-enabled=false", toolName);
                    continue;
                }
                MeteredToolCallback metered = new MeteredToolCallback(rawTool, mcpToolMetrics,
                        returnToolErrorOnFailure, githubWriteEnabled,
                        githubSearchMaxPerPage, githubSearchMaxResultChars,
                        githubSearchCompactResultEnabled, aiSearchStripServerLlm,
                        mcpToolCallMaxAttempts, mcpToolCallRetryDelayMs);
                metered.setHumanApprovalGate(humanApprovalGate); // G1-C
                metered.setToolCallProgressEmitter(toolCallProgressEmitter); // H3-A
                metered.setToolCallLedger(toolCallLedger); // P0 Codex#2：工具调用实证台账
                meteredToolList.add(metered);
            }
            // LLM 工具名大小写幻觉不再用 alias 翻倍 prompt 解决，
            // 改由自定义 ToolCallingManager 在 lookup 时做 case-insensitive 匹配（见 AiClientModelNode）。
            ToolCallback[] meteredTools = meteredToolList.toArray(new ToolCallback[]{});
            ToolCallback[] registryTools = meteredTools;
            boolean hasEffectiveTools = meteredTools.length > 0;
            if (!hasEffectiveTools && chatModel.getDefaultOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tco) {
                List<ToolCallback> modelTools = tco.getToolCallbacks();
                if (modelTools != null && !modelTools.isEmpty()) {
                    registryTools = modelTools.toArray(new ToolCallback[]{});
                    hasEffectiveTools = true;
                    log.info("[AiClientNode] clientId={} client级无MCP工具，从model继承{}个工具",
                            aiClientVO.getClientId(), registryTools.length);
                }
            }

            ChatClient chatClient = ChatClient.builder(chatModel)
                    // P2-B-1：公共信任边界 prepend 到 defaultSystem 之前（不改 DB prompt 内容）。固定前缀，prompt cache 仍稳定。
                    .defaultSystem(cn.bugstack.ai.domain.agent.service.prompt.SystemPolicyComposer.prepend(defaultSystem.toString()))
                    .defaultToolCallbacks(meteredTools)
                    .defaultAdvisors(advisorArray)
                    .build();

            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, chatClient);

            agentToolRegistry.register(aiClientVO.getClientId(), registryTools);
        }

        return router(requestParameter, dynamicContext);
    }

    private List<String> collectAgentMemoryAdvisorBeanNames(List<AiClientVO> aiClientList) {
        Set<String> names = new LinkedHashSet<>();
        if (aiClientList == null) return new ArrayList<>();
        for (AiClientVO aiClientVO : aiClientList) {
            List<String> advisorBeanNameList = aiClientVO.getAdvisorBeanNameList();
            if (advisorBeanNameList == null) continue;
            for (String advisorBeanName : advisorBeanNameList) {
                if (advisorBeanName == null || advisorBeanName.isBlank() || names.contains(advisorBeanName)) continue;
                try {
                    Object advisor = getBean(advisorBeanName);
                    if (advisor instanceof LongTermMemoryAdvisor
                            || advisor instanceof EpisodicMemoryAdvisor
                            || advisor instanceof ReadOnlyChatMemoryAdvisor) {
                        names.add(advisorBeanName);
                    }
                } catch (Exception e) {
                    log.warn("[AiClientNode] advisor bean {} missing while collecting agent memory advisors: {}",
                            advisorBeanName, e.getMessage());
                }
            }
        }
        return new ArrayList<>(names);
    }

    private String extractToolMcpId(String mcpBeanName) {
        if (mcpBeanName == null || mcpBeanName.isBlank()) {
            return null;
        }
        String prefix = AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName("");
        return mcpBeanName.startsWith(prefix) ? mcpBeanName.substring(prefix.length()) : null;
    }

    private List<String> mergeAdvisorBeanNames(List<String> local, List<String> agentMemory) {
        Set<String> merged = new LinkedHashSet<>();
        if (local != null) {
            for (String name : local) {
                if (name != null && !name.isBlank()) merged.add(name);
            }
        }
        if (agentMemory != null) {
            for (String name : agentMemory) {
                if (name != null && !name.isBlank()) merged.add(name);
            }
        }
        return new ArrayList<>(merged);
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
