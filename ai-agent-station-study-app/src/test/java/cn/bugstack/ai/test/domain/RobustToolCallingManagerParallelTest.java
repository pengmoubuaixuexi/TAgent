package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.McpToolCallMetadata;
import cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 {@link RobustToolCallingManager} 的工具并行执行：
 * <ol>
 *   <li>同一轮 ≥2 个 tool call → 切片并行委托 delegate（每次单工具）→ 按原序合并成一个 ToolResponseMessage</li>
 *   <li>parallel-enabled=false → 原样一次性委托 delegate（串行）</li>
 * </ol>
 * 用手写 fake delegate 模拟 DefaultToolCallingManager 的合约，不依赖 Mockito。
 */
public class RobustToolCallingManagerParallelTest {

    /** fake delegate：按传入 ChatResponse 里的 tool call 逐条产出 "result-of-<name>" 的 ToolResponse。 */
    static class FakeDelegate implements ToolCallingManager {
        final AtomicInteger callCount = new AtomicInteger();
        final List<Integer> toolCountsPerCall = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            callCount.incrementAndGet();
            AssistantMessage am = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> calls = am.getToolCalls();
            toolCountsPerCall.add(calls.size());
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : calls) {
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "result-of-" + tc.name()));
            }
            List<Message> history = new ArrayList<>(prompt.getInstructions());
            history.add(am);
            history.add(ToolResponseMessage.builder().responses(responses).metadata(Map.of()).build());
            return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
        }
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }
            @Override
            public String call(String toolInput) {
                return "x";
            }
            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return "x";
            }
        };
    }

    private static Prompt promptWithTools(String... toolNames) {
        ToolCallback[] cbs = new ToolCallback[toolNames.length];
        for (int i = 0; i < toolNames.length; i++) cbs[i] = callback(toolNames[i]);
        OpenAiChatOptions options = OpenAiChatOptions.builder().toolCallbacks(cbs)
                .toolContext(Map.of("agent.tool_capabilities", "BUSINESS_ONLY")).build();
        return new Prompt("用户问题", options);
    }

    private static Prompt promptWithMcpTools(Map<String, String> identities) {
        ToolCallback[] callbacks = identities.entrySet().stream().map(entry -> (ToolCallback) new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return callback(entry.getKey()).getToolDefinition(); }
            @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return new McpToolCallMetadata(entry.getValue(), true, false, null, false);
            }
            @Override public String call(String input) { return "result-of-" + entry.getKey(); }
        }).toArray(ToolCallback[]::new);
        return new Prompt("用户问题", OpenAiChatOptions.builder().toolCallbacks(callbacks)
                .toolContext(Map.of("agent.tool_capabilities", "BUSINESS_ONLY")).build());
    }

    private static Prompt promptWithToolsAndPriorRounds(int priorRounds, String... toolNames) {
        ToolCallback[] cbs = new ToolCallback[toolNames.length];
        for (int i = 0; i < toolNames.length; i++) cbs[i] = callback(toolNames[i]);
        OpenAiChatOptions options = OpenAiChatOptions.builder().toolCallbacks(cbs)
                .toolContext(Map.of("agent.tool_capabilities", "BUSINESS_ONLY")).build();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("用户问题"));
        for (int i = 0; i < priorRounds; i++) {
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("prior-" + i, toolNames[0], "prior-result"))).metadata(Map.of()).build());
        }
        return new Prompt(messages, options);
    }

    private static ChatResponse responseWithToolCalls(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String n : toolNames) {
            calls.add(new AssistantMessage.ToolCall("id-" + n, "function", n, "{}"));
        }
        AssistantMessage am = AssistantMessage.builder().content("").properties(Map.of()).toolCalls(calls).build();
        return new ChatResponse(List.of(new Generation(am)));
    }

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    /** 建一个 toolName→mcpId 映射的注册表（方案A 按 mcpId 分组用）。 */
    private static McpClientRegistry registry(Map<String, String> toolToMcp) {
        McpClientRegistry reg = new McpClientRegistry();
        toolToMcp.values().stream().distinct().forEach(mcp -> reg.registerCallbacks(mcp,
                toolToMcp.entrySet().stream().filter(entry -> mcp.equals(entry.getValue()))
                        .map(entry -> callback(entry.getKey())).toArray(ToolCallback[]::new)));
        return reg;
    }

    @Test
    public void parallel_differentMcpServers_executesPerToolAndMergesInOrder() {
        FakeDelegate delegate = new FakeDelegate();
        ThreadPoolExecutor exec = pool();
        try {
            // toolA / toolB 在不同 MCP server → 2 组 → 组间并行，各自单工具委托
            McpClientRegistry reg = registry(Map.of("toolA", "mcpA", "toolB", "mcpB"));
            RobustToolCallingManager mgr = new RobustToolCallingManager(delegate, null, exec, true, reg);

            ToolExecutionResult result = mgr.executeToolCalls(
                    promptWithMcpTools(Map.of("toolA", "mcpA", "toolB", "mcpB")), responseWithToolCalls("toolA", "toolB"));

            // 不同 server → 每组单工具委托一次
            assertEquals("不同 MCP server 的工具应各委托一次", 2, delegate.callCount.get());
            for (int n : delegate.toolCountsPerCall) {
                assertEquals("不同 server 组每次只委托单个 tool call", 1, n);
            }

            // conversationHistory 末尾是一个含 2 条响应、且按原序的 ToolResponseMessage
            List<Message> history = result.conversationHistory();
            Message last = history.get(history.size() - 1);
            assertTrue(last instanceof ToolResponseMessage);
            List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) last).getResponses();
            assertEquals(2, responses.size());
            assertEquals("toolA", responses.get(0).name());
            assertEquals("result-of-toolA", responses.get(0).responseData());
            assertEquals("toolB", responses.get(1).name());
            assertEquals("result-of-toolB", responses.get(1).responseData());

            // 结构：倒数第二条是含 2 个 tool call 的原始 AssistantMessage
            Message assistant = history.get(history.size() - 2);
            assertTrue(assistant instanceof AssistantMessage);
            assertEquals(2, ((AssistantMessage) assistant).getToolCalls().size());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void sameMcpServer_serializesViaDelegate() {
        FakeDelegate delegate = new FakeDelegate();
        ThreadPoolExecutor exec = pool();
        try {
            // toolA / toolB 同一个 MCP server → 1 组 → 退回 delegate 原生串行（连接非并发安全）
            McpClientRegistry reg = registry(Map.of("toolA", "mcpA", "toolB", "mcpA"));
            RobustToolCallingManager mgr = new RobustToolCallingManager(delegate, null, exec, true, reg);

            mgr.executeToolCalls(promptWithMcpTools(Map.of("toolA", "mcpA", "toolB", "mcpA")), responseWithToolCalls("toolA", "toolB"));

            assertEquals("同一 MCP server 应退回 delegate 一次性串行", 1, delegate.callCount.get());
            assertEquals(1, delegate.toolCountsPerCall.size());
            assertEquals("委托时应带全部 2 个 tool call", 2, delegate.toolCountsPerCall.get(0).intValue());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void flagOff_delegatesOnce_sequentially() {
        FakeDelegate delegate = new FakeDelegate();
        ThreadPoolExecutor exec = pool();
        try {
            McpClientRegistry reg = registry(Map.of("toolA", "mcpA", "toolB", "mcpB"));
            RobustToolCallingManager mgr = new RobustToolCallingManager(delegate, null, exec, false, reg);

            mgr.executeToolCalls(promptWithTools("toolA", "toolB"), responseWithToolCalls("toolA", "toolB"));

            assertEquals("并行关闭时应一次性委托 delegate", 1, delegate.callCount.get());
            assertEquals(1, delegate.toolCountsPerCall.size());
            assertEquals("委托时应带全部 2 个 tool call", 2, delegate.toolCountsPerCall.get(0).intValue());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void serialRoundLimit_appliesEvenWhenParallelDisabled() {
        FakeDelegate delegate = new FakeDelegate();
        ThreadPoolExecutor exec = pool();
        try {
            McpClientRegistry reg = registry(Map.of("toolA", "mcpA"));
            RobustToolCallingManager mgr = new RobustToolCallingManager(delegate, null, exec, false, reg, 1);

            ToolExecutionResult result = mgr.executeToolCalls(
                    promptWithToolsAndPriorRounds(1, "toolA"), responseWithToolCalls("toolA"));

            assertEquals("超过轮次预算时不应继续真实执行工具", 0, delegate.callCount.get());
            List<Message> history = result.conversationHistory();
            Message last = history.get(history.size() - 1);
            assertTrue(last instanceof ToolResponseMessage);
            String data = ((ToolResponseMessage) last).getResponses().get(0).responseData();
            assertTrue(data.contains("最多只允许 1 轮"));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void singleToolCall_takesSequentialPath_evenWhenParallelEnabled() {
        FakeDelegate delegate = new FakeDelegate();
        ThreadPoolExecutor exec = pool();
        AtomicReference<ToolExecutionResult> ref = new AtomicReference<>();
        try {
            McpClientRegistry reg = registry(Map.of("toolA", "mcpA"));
            RobustToolCallingManager mgr = new RobustToolCallingManager(delegate, null, exec, true, reg);
            ref.set(mgr.executeToolCalls(promptWithTools("toolA"), responseWithToolCalls("toolA")));

            // 单工具：N<2，不进并行分支，直接委托一次带 1 个 tool call
            assertEquals(1, delegate.callCount.get());
            assertEquals(1, delegate.toolCountsPerCall.get(0).intValue());
            assertTrue(ref.get().conversationHistory().size() >= 1);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void anotherUsersSameNamedToolsCannotDisableCurrentRequestsParallelism() {
        ThreadPoolExecutor exec = pool();
        CountDownLatch bothEntered = new CountDownLatch(2);
        AtomicInteger overlapped = new AtomicInteger();
        AtomicInteger unrelatedCalls = new AtomicInteger();
        McpClientRegistry reg = new McpClientRegistry() {
            @Override public String getMcpIdForTool(String name) {
                throw new AssertionError("request scheduling must not use the global name index");
            }
        };
        for (String owner : List.of("alice", "bob")) {
            reg.registerCallbacks(owner + "-map", new ToolCallback[]{callback("map_search")});
            reg.registerCallbacks(owner + "-web", new ToolCallback[]{callback("web_search")});
        }
        List<ToolCallback> ownCallbacks = new ArrayList<>();
        for (String name : List.of("map_search", "web_search")) {
            ToolCallback own = new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return callback(name).getToolDefinition(); }
                @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                    return new McpToolCallMetadata("alice-" + name, true, false, null, false);
                }
                @Override public String call(String input) {
                    bothEntered.countDown();
                    try {
                        if (bothEntered.await(2, TimeUnit.SECONDS)) overlapped.incrementAndGet();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return name;
                }
            };
            ownCallbacks.add(new HintedToolCallback(own, "test hint survives decoration"));
        }
        reg.registerCallbacks("unrelated", new ToolCallback[]{new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return callback("map_search").getToolDefinition(); }
            @Override public String call(String input) { unrelatedCalls.incrementAndGet(); return "wrong owner"; }
        }});
        try {
            Prompt prompt = new Prompt("query", OpenAiChatOptions.builder().toolCallbacks(ownCallbacks)
                    .toolContext(Map.of("agent.tool_capabilities", "BUSINESS_ONLY")).build());
            RobustToolCallingManager manager = new RobustToolCallingManager(ToolCallingManager.builder().build(), null, exec, true, reg);
            ToolExecutionResult result = manager.executeToolCalls(prompt, responseWithToolCalls("map_search", "web_search"));
            assertEquals("both actual callbacks must overlap", 2, overlapped.get());
            assertEquals(0, unrelatedCalls.get());
            ToolResponseMessage response = (ToolResponseMessage) result.conversationHistory().get(result.conversationHistory().size() - 1);
            assertEquals(List.of("map_search", "web_search"), response.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData).toList());
        } finally { exec.shutdownNow(); }
    }

    @Test
    public void callbacksWithoutConnectionMetadataRemainConservativelySerial() {
        FakeDelegate delegate = new FakeDelegate();
        ThreadPoolExecutor exec = pool();
        try {
            RobustToolCallingManager manager = new RobustToolCallingManager(delegate, null, exec, true,
                    registry(Map.of("toolA", "mcpA", "toolB", "mcpB")));
            manager.executeToolCalls(promptWithTools("toolA", "toolB"), responseWithToolCalls("toolA", "toolB"));
            assertEquals(1, delegate.callCount.get());
            assertEquals(2, delegate.toolCountsPerCall.get(0).intValue());
        } finally { exec.shutdownNow(); }
    }
}
