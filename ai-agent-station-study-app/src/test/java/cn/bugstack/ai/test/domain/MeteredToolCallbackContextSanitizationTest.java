package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.McpToolMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.MeteredToolCallback;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class MeteredToolCallbackContextSanitizationTest {

    @Test
    public void stripsFrameworkHistoryButPreservesBusinessContextForMcpCall() {
        AtomicReference<ToolContext> received = new AtomicReference<>();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("webSearch")
                        .description("test")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "unexpected";
            }

            @Override
            public String call(String input, ToolContext context) {
                received.set(context);
                return "ok";
            }
        };
        MeteredToolCallback callback = new MeteredToolCallback(
                delegate, new McpToolMetrics(new SimpleMeterRegistry()));

        UserMessage imageMessage = UserMessage.builder()
                .text("describe")
                .media(List.of(new Media(
                        MimeType.valueOf("image/png"),
                        URI.create("https://example.com/image.png"))))
                .build();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sessionId", "session-1");
        values.put("stepLabel", "answer");
        values.put(ToolContext.TOOL_CALL_HISTORY, List.of(imageMessage));
        ToolContext original = new ToolContext(values);

        assertEquals("ok", callback.call("{\"query\":\"agent\"}", original));

        ToolContext sanitized = received.get();
        assertNotSame(original, sanitized);
        assertEquals("session-1", sanitized.getContext().get("sessionId"));
        assertEquals("answer", sanitized.getContext().get("stepLabel"));
        assertFalse(sanitized.getContext().containsKey(ToolContext.TOOL_CALL_HISTORY));
        assertTrue(original.getContext().containsKey(ToolContext.TOOL_CALL_HISTORY));
        assertEquals(1, original.getToolCallHistory().get(0) instanceof UserMessage user
                ? user.getMedia().size() : 0);
    }
}
