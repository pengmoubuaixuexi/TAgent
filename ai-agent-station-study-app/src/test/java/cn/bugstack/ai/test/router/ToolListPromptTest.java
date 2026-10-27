package cn.bugstack.ai.test.router;

import cn.bugstack.ai.domain.agent.service.router.AgentToolRegistry;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The tool list must not tell the model to answer from its own knowledge while request_tool is offered. */
public class ToolListPromptTest {
    private final AgentToolRegistry registry = new AgentToolRegistry();

    @Test
    public void noBoundToolsPointsToRequestToolWhenItIsOffered() {
        String text = registry.describeToolsForPrompt("c1", List.of(), true);
        assertTrue(text, text.contains("request_tool"));
        assertFalse(text, text.contains("仅基于 LLM 自身知识"));
    }

    @Test
    public void noBoundToolsAndNoRequestToolUsesEvidenceWithoutInventingData() {
        String text = registry.describeToolsForPrompt("c1", List.of());
        assertFalse(text, text.contains("仅基于 LLM 自身知识"));
        assertTrue(text, text.contains("实际提供的平台工具"));
        assertTrue(text, text.contains("不要用常识猜测替代查询"));
        assertFalse(text, text.contains("request_tool"));
    }

    @Test
    public void boundToolsMentionRequestToolOnlyWhenOffered() {
        ToolCallback weather = mock(ToolCallback.class);
        when(weather.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("weather_forecast").description("查询天气预报").inputSchema("{}").build());
        registry.register("c2", new ToolCallback[]{weather});
        assertTrue(registry.describeToolsForPrompt("c2", List.of(), true).contains("request_tool"));
        String without = registry.describeToolsForPrompt("c2", List.of(), false);
        assertTrue(without.contains("weather_forecast"));
        assertFalse(without.contains("request_tool"));
    }
}
