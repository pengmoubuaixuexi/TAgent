package cn.bugstack.ai.test.prompt;

import cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog;
import cn.bugstack.ai.domain.agent.service.prompt.RuntimeToolPromptComposer;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-B-3 RuntimeToolPromptComposer 渲染骨架测试（v3.md §80）：&lt;tool_runtime&gt; 由 catalog schema 派生 +
 * 元工具说明收编。Flow Step1/2/4 注入点替换 + profile/gate 接入由 Codex 接力，本测试只锁渲染纯函数。
 */
public class RuntimeToolPromptComposerTest {

    private static ToolCallback cb(String name, String desc, String schema) {
        ToolCallback c = mock(ToolCallback.class);
        when(c.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name(name).description(desc).inputSchema(schema).build());
        return c;
    }

    @Test
    public void renderToolRuntime_includesNameDescAndNormalizedSchema() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(
                List.of(cb("calc", "计算器", "{\"type\":\"object\"}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        String out = RuntimeToolPromptComposer.renderToolRuntime(cat);
        assertTrue(out.contains("<tool_runtime>"));
        assertTrue(out.contains("<tool name=\"calc\">"));
        assertTrue(out.contains("<description>计算器</description>"));
        assertTrue("normalized schema 来自 catalog", out.contains("type=object"));
        assertTrue(out.contains("</tool_runtime>"));
    }

    @Test
    public void renderToolRuntime_emptyCatalog_emptyString() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(null, ExecutorToolCatalog.Source.RESIDENT, 0);
        assertEquals("", RuntimeToolPromptComposer.renderToolRuntime(cat));
        assertEquals("", RuntimeToolPromptComposer.renderToolRuntime(null));
    }

    @Test
    public void renderMetaToolHint_byAvailability() {
        assertEquals("", RuntimeToolPromptComposer.renderMetaToolHint(false, false));
        String both = RuntimeToolPromptComposer.renderMetaToolHint(true, true);
        assertTrue(both.contains("ask_user"));
        assertTrue(both.contains("request_tool"));
        String askOnly = RuntimeToolPromptComposer.renderMetaToolHint(true, false);
        assertTrue(askOnly.contains("ask_user"));
        assertFalse(askOnly.contains("request_tool"));
        String reqOnly = RuntimeToolPromptComposer.renderMetaToolHint(false, true);
        assertFalse(reqOnly.contains("ask_user"));
        assertTrue(reqOnly.contains("request_tool"));
    }

    @Test
    public void renderToolRuntime_escapesAngleBracketsInContent() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(
                List.of(cb("a", "desc<x>", "{}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        String out = RuntimeToolPromptComposer.renderToolRuntime(cat);
        assertTrue(out.contains("desc&lt;x&gt;"));
    }
}
