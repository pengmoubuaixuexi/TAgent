package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 对话模型节点配置
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/5 12:43
 */
@Slf4j
@Service
public class AiClientModelNode extends AbstractArmorySupport {

    @Resource
    private AiClientAdvisorNode aiClientAdvisorNode;

    /** P1.5.1：每个 ToolCallback 包一层装饰器，统一打 mcp.tool.call metric */
    @Resource
    private McpToolMetrics mcpToolMetrics;

    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry mcpClientRegistry;

    /** T10：工具调用 prompt hint 注册表 */
    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolPromptHintRegistry toolPromptHintRegistry;

    /** G1-C：人工审批 gate（可选），装配时 setter 注入到 MeteredToolCallback */
    @Resource
    private cn.bugstack.ai.domain.agent.service.security.HumanApprovalGate humanApprovalGate;

    /** H3-A：工具调用进度 SSE emitter，装配时 setter 注入到 MeteredToolCallback */
    @Resource
    private cn.bugstack.ai.domain.agent.service.execute.common.ToolCallProgressEmitter toolCallProgressEmitter;

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

    /** 工具并行执行开关；true 时同一轮 ≥2 个 tool call 并行跑 */
    @Value("${agent.mcp.tool-call.parallel-enabled:true}")
    private boolean toolCallParallelEnabled;

    /** 单个 client 执行链路内最多允许的串行工具轮数；0 表示不限制。并行的一批 tool call 算 1 轮。 */
    @Value("${agent.mcp.tool-call.max-serial-rounds-per-client:3}")
    private int toolCallMaxSerialRoundsPerClient;

    /** (a) 非执行步（分析/规划/质检/汇总）禁工具：开 → 这些步不向模型暴露工具定义，模型不会 tool_call；关 → 所有步保留工具（旧行为）。 */
    @Value("${agent.mcp.disable-tools-on-nonexec-steps:true}")
    private boolean disableToolsOnNonExecStep;

    /** 并行工具执行线程池（execute() 自动 ContextSnapshot.wrap 接力 MDC），与 flow DAG step 共用 */
    @Resource(name = "dagExecutor")
    private ThreadPoolExecutor dagExecutor;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Mode 对话模型{}", JSON.toJSONString(requestParameter));

        List<AiClientModelVO> aiClientModelList = dynamicContext.getValue(dataName());

        if (aiClientModelList == null || aiClientModelList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client model");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientModelVO modelVO : aiClientModelList) {

            // 获取当前模型关联的 API Bean 对象
            OpenAiApi openAiApi = getBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName(modelVO.getApiId()));
            if (null == openAiApi) {
                throw new RuntimeException("mode 2 api is null");
            }

            // 获取当前模型关联的 Tool MCP Bean 对象（个别 MCP 初始化失败已被前置节点跳过，这里做容错）
            // 按 mcpId 分别获取回调，建立 toolName → mcpId 映射，注册到 McpClientRegistry
            List<ToolCallback> allRawCallbacks = new ArrayList<>();
            for (String toolMcpId : modelVO.getToolMcpIds()) {
                try {
                    McpSyncClient mcpSyncClient = getBean(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(toolMcpId));
                    ToolCallback[] mcpCallbacks = new SyncMcpToolCallbackProvider(List.of(mcpSyncClient)).getToolCallbacks();
                    mcpClientRegistry.registerCallbacks(toolMcpId, mcpCallbacks);
                    allRawCallbacks.addAll(java.util.Arrays.asList(mcpCallbacks));
                } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException ex) {
                    log.warn("[AiClientModelNode] MCP {} 缺失，跳过: {}", toolMcpId, ex.getMessage());
                }
            }

            // P1.5.1：原 ToolCallback 数组逐一包成 MeteredToolCallback，让 mcp.tool.call metric 生效
            // 2026-05-07 #1 Prompt Cache：按 toolDefinition.name() 字典序排序后再装配，
            // 工具 schema 在 prompt 中的位置稳定 → OpenAI 自动 prompt cache 才能命中
            ToolCallback[] rawCallbacks = allRawCallbacks.toArray(new ToolCallback[0]);
            java.util.Arrays.sort(rawCallbacks, java.util.Comparator.comparing(t ->
                    t.getToolDefinition() == null ? "" : t.getToolDefinition().name()));
            ToolCallback[] meteredCallbacks = new ToolCallback[rawCallbacks.length];
            for (int i = 0; i < rawCallbacks.length; i++) {
                String toolName = rawCallbacks[i].getToolDefinition() != null ? rawCallbacks[i].getToolDefinition().name() : "";
                String toolMcpId = mcpClientRegistry.getMcpIdForTool(toolName);
                // T10：先包一层 HintedToolCallback 把 prompt hint 拼进 description，再交给 MeteredToolCallback
                // 顺序固定：raw -> hinted -> metered。hint 命中才 wrap，命不中零开销跳过。
                String hint = toolPromptHintRegistry != null ? toolPromptHintRegistry.getHint(toolName) : null;
                ToolCallback hinted = (hint != null && !hint.isBlank())
                        ? new cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback(rawCallbacks[i], hint)
                        : rawCallbacks[i];
                MeteredToolCallback metered = new MeteredToolCallback(hinted, mcpToolMetrics,
                        returnToolErrorOnFailure, githubWriteEnabled,
                        githubSearchMaxPerPage, githubSearchMaxResultChars,
                        githubSearchCompactResultEnabled, aiSearchStripServerLlm,
                        mcpToolCallMaxAttempts, mcpToolCallRetryDelayMs,
                        mcpClientRegistry, toolMcpId);
                metered.setHumanApprovalGate(humanApprovalGate); // G1-C
                metered.setToolCallProgressEmitter(toolCallProgressEmitter); // H3-A
                meteredCallbacks[i] = metered;
                if (hint != null && !hint.isBlank()) {
                    log.debug("[AiClientModelNode] applied prompt hint to tool {}: {}", toolName, hint);
                }
            }

            // 实例化对话模型（如果有其他模型对接，可以使用 one-api 服务，转换为 openai 模型格式）
            // P1.5.2：只有有工具时才允许设置 parallelToolCalls，避免 OpenAI 报
            // 'parallel_tool_calls' is only allowed when 'tools' are specified。
            // 是否真正开启并行工具调用由 agent.mcp.tool-call.parallel-enabled 控制。
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .model(modelVO.getModelName())
                    .toolCallbacks(meteredCallbacks);
            if (meteredCallbacks.length > 0) {
                optionsBuilder.parallelToolCalls(toolCallParallelEnabled);
            }
            // 2026-05-08 #4：注入自定义 ToolCallingManager 解决 LLM 工具名大小写幻觉问题。
            // RobustToolCallingManager 在执行前对 ChatResponse 里的 ToolCall.name 做 case-insensitive
            // 校正，避免 LLM 输出 "AISEARCH" 时 DefaultToolCallingManager equals 失败抛 IllegalStateException。
            org.springframework.ai.model.tool.ToolCallingManager defaultMgr =
                    org.springframework.ai.model.tool.DefaultToolCallingManager.builder().build();
            cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager robustMgr =
                    new cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager(
                            defaultMgr, mcpToolMetrics, dagExecutor, toolCallParallelEnabled, mcpClientRegistry,
                            toolCallMaxSerialRoundsPerClient);
            // (a) 非执行步禁工具：开关开时，resolveToolDefinitions 对分析/规划/质检/汇总步返回空工具集
            // → 模型请求无 tools 字段 → 模型不会 tool_call（从源头掐，非事后拦截）。关时所有步保留工具（旧行为）。
            robustMgr.setDisableToolsOnNonExecStep(disableToolsOnNonExecStep);

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(optionsBuilder.build())
                    .toolCallingManager(robustMgr)
                    .build();

            // 注册 Bean 对象
            registerBean(beanName(modelVO.getModelId()), OpenAiChatModel.class, chatModel);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientAdvisorNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getDataName();
    }

}
