package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP客户端配置节点
 *
 * @author TAgent
 * 2025/7/5 12:48
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport {

    @Resource
    private AiClientModelNode aiClientModelNode;

    @Resource
    private McpClientRegistry mcpClientRegistry;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Tool MCP 工具配置{}", JSON.toJSONString(requestParameter));

        List<AiClientToolMcpVO> aiClientToolMcpList = dynamicContext.getValue(dataName());

        if (aiClientToolMcpList == null || aiClientToolMcpList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client tool mcp");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientToolMcpVO mcpVO : aiClientToolMcpList) {
            try {
                // 创建 MCP 服务
                McpSyncClient mcpSyncClient = createMcpSyncClient(mcpVO);

                // 注册 MCP 对象
                registerBean(beanName(mcpVO.getMcpId()), McpSyncClient.class, mcpSyncClient);

                // 注册到 McpClientRegistry（用于 SSE 断连后自动重建）
                mcpClientRegistry.register(mcpVO.getMcpId(), mcpVO, mcpSyncClient, this::createMcpSyncClient);
            } catch (Exception ex) {
                // 单个 MCP 不可达不应阻塞整个 agent 装配（否则一个坏 URL 让所有 agent 全废）
                log.warn("[McpInit] MCP id={} name={} 初始化失败，已跳过：{}",
                        mcpVO.getMcpId(), mcpVO.getMcpName(), ex.toString());
            }
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientModelNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName();
    }

    public McpSyncClient createMcpSyncClient(AiClientToolMcpVO aiClientToolMcpVO) {
        String transportType = aiClientToolMcpVO.getTransportType();

        switch (transportType) {
            case "sse" -> {
                AiClientToolMcpVO.TransportConfigSse transportConfigSse = aiClientToolMcpVO.getTransportConfigSse();
                // http://127.0.0.1:9999/sse?apikey=DElk89iu8Ehhnbu
                String originalBaseUri = transportConfigSse.getBaseUri();
                String baseUri;
                String sseEndpoint;

                int queryParamStartIndex = originalBaseUri.indexOf("sse");
                if (queryParamStartIndex != -1) {
                    baseUri = originalBaseUri.substring(0, queryParamStartIndex - 1);
                    sseEndpoint = originalBaseUri.substring(queryParamStartIndex - 1);
                } else {
                    baseUri = originalBaseUri;
                    sseEndpoint = transportConfigSse.getSseEndpoint();
                }

                sseEndpoint = StringUtils.isBlank(sseEndpoint) ? "/sse" : sseEndpoint;

                HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                        .builder(baseUri) // 使用截取后的 baseUri
                        .sseEndpoint(sseEndpoint) // 使用截取或默认的 sseEndpoint
                        .build();

                McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofSeconds(aiClientToolMcpVO.getRequestTimeout())).build();
                var init_sse = mcpSyncClient.initialize();

                log.info("Tool SSE MCP Initialized {}", init_sse);
                return mcpSyncClient;
            }
            case "stdio" -> {
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = aiClientToolMcpVO.getTransportConfigStdio();
                Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdioMap = transportConfigStdio.getStdio();
                AiClientToolMcpVO.TransportConfigStdio.Stdio stdio = stdioMap.get(aiClientToolMcpVO.getMcpName());

                // https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem
                Map<String, String> envMap = resolveInlineJsonEnv(stdio.getEnv());
                List<String> resolvedArgs = resolveRelativePaths(stdio.getArgs());

                var stdioParams = ServerParameters.builder(stdio.getCommand())
                        .args(resolvedArgs)
                        .env(envMap)
                        .build();

                var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams))
                        .requestTimeout(Duration.ofSeconds(aiClientToolMcpVO.getRequestTimeout())).build();
                var init_stdio = mcpClient.initialize();

                log.info("Tool Stdio MCP Initialized {}", init_stdio);
                return mcpClient;
            }
        }

        throw new RuntimeException("err! transportType " + transportType + " not exist!");
    }

    /**
     * MCP 子进程 args 中可能包含相对文件路径（如 mcp-stdio-wrapper.js）。
     * 当 CWD 不是项目根目录时（如 IDE 从子模块启动），这些路径会找不到。
     * 此方法检测相对路径，如果 CWD 下不存在但项目根下存在，则替换为绝对路径。
     */
    private List<String> resolveRelativePaths(List<String> args) {
        if (args == null || args.isEmpty()) return args;

        String projectRoot = findProjectRoot();
        if (projectRoot == null) return args;

        List<String> resolved = new ArrayList<>(args.size());
        for (String arg : args) {
            if (!arg.startsWith("-") && (arg.contains("/") || arg.contains("\\"))) {
                File asIs = new File(arg);
                if (!asIs.isAbsolute() && !asIs.exists()) {
                    File fromRoot = new File(projectRoot, arg);
                    if (fromRoot.exists()) {
                        String absPath = fromRoot.getAbsolutePath();
                        log.info("[McpPath] 相对路径修正: {} -> {}", arg, absPath);
                        resolved.add(absPath);
                        continue;
                    }
                }
            }
            resolved.add(arg);
        }
        return resolved;
    }

    /** 向上遍历查找包含 .git 目录的项目根 */
    private String findProjectRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, ".git").exists()) {
                return dir.getAbsolutePath();
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * 处理 env 中内嵌 JSON 的场景。
     * 如果某个 env 值以 '{' 开头且以 '}' 结尾，说明是内嵌 JSON 内容（如 Google OAuth 凭证），
     * 自动写入临时文件并将 env 值替换为文件路径，这样 MCP 子进程就能正确读取。
     */
    private Map<String, String> resolveInlineJsonEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return env;
        }
        Map<String, String> resolved = new HashMap<>(env);
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.startsWith("{") && value.endsWith("}")) {
                try {
                    File tempFile = File.createTempFile("mcp-env-", ".json");
                    tempFile.deleteOnExit();
                    Files.write(tempFile.toPath(), value.getBytes(StandardCharsets.UTF_8));
                    entry.setValue(tempFile.getAbsolutePath());
                    log.info("[McpEnv] 内嵌 JSON 已写入临时文件: key={} file={}", entry.getKey(), tempFile.getAbsolutePath());
                } catch (Exception e) {
                    log.warn("[McpEnv] 写入临时文件失败: key={}", entry.getKey(), e);
                }
            }
        }
        return resolved;
    }

}
