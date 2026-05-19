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

/**
 * 对话模型节点配置
 *
 * @author TAgent
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

    @Value("${agent.llm.model:}")
    private String llmModel;

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
                meteredCallbacks[i] = new MeteredToolCallback(rawCallbacks[i], mcpToolMetrics,
                        returnToolErrorOnFailure, githubWriteEnabled,
                        githubSearchMaxPerPage, githubSearchMaxResultChars,
                        githubSearchCompactResultEnabled, aiSearchStripServerLlm,
                        mcpToolCallMaxAttempts, mcpToolCallRetryDelayMs,
                        mcpClientRegistry, toolMcpId);
            }

            // 实例化对话模型（如果有其他模型对接，可以使用 one-api 服务，转换为 openai 模型格式）
            // P1.5.2：有工具时才开 parallelToolCalls，避免 OpenAI 报 'parallel_tool_calls' is only allowed when 'tools' are specified
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .model(OpenAiCompatibleApiSupport.valueOrDefault(llmModel, modelVO.getModelName()))
                    .toolCallbacks(meteredCallbacks);
            if (meteredCallbacks.length > 0) {
                optionsBuilder.parallelToolCalls(true);
            }
            // 2026-05-08 #4：注入自定义 ToolCallingManager 解决 LLM 工具名大小写幻觉问题。
            // RobustToolCallingManager 在执行前对 ChatResponse 里的 ToolCall.name 做 case-insensitive
            // 校正，避免 LLM 输出 "AISEARCH" 时 DefaultToolCallingManager equals 失败抛 IllegalStateException。
            org.springframework.ai.model.tool.ToolCallingManager defaultMgr =
                    org.springframework.ai.model.tool.DefaultToolCallingManager.builder().build();
            cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager robustMgr =
                    new cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager(defaultMgr);

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
