package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.HintedToolCallback;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolPromptHintRegistry;
import org.junit.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T10 单元测试：验证 ToolPromptHintRegistry 匹配 + HintedToolCallback wrap 行为不破坏原 ToolDefinition。
 */
public class ToolPromptHintRegistryTest {

    @Test
    public void exactMatchReturnsHint() {
        ToolPromptHintRegistry r = new ToolPromptHintRegistry();
        r.initDefaults();
        assertNotNull(r.getHint("search_repositories"));
        assertTrue(r.getHint("search_repositories").contains("per_page"));
    }

    @Test
    public void suffixMatchHandlesMcpClientPrefix() {
        ToolPromptHintRegistry r = new ToolPromptHintRegistry();
        r.initDefaults();
        // 真实 MCP 调用名常带前缀
        String hint = r.getHint("spring_ai_mcp_client_github_search_repositories");
        assertNotNull(hint);
        assertTrue(hint.contains("per_page"));
    }

    @Test
    public void suffixMatchPrefersLongestKey() {
        ToolPromptHintRegistry r = new ToolPromptHintRegistry();
        java.util.Map<String, String> hints = new java.util.HashMap<>();
        hints.put("repositories", "generic repositories hint");
        hints.put("search_repositories", "specific search repositories hint");
        r.setToolHint(hints);
        r.initDefaults();
        assertEquals("specific search repositories hint",
                r.getHint("spring_ai_mcp_client_github_search_repositories"));
    }

    @Test
    public void unknownToolReturnsNull() {
        ToolPromptHintRegistry r = new ToolPromptHintRegistry();
        r.initDefaults();
        assertNull(r.getHint("totally_unknown_tool_xyz"));
    }

    @Test
    public void emptyOrNullToolName() {
        ToolPromptHintRegistry r = new ToolPromptHintRegistry();
        r.initDefaults();
        assertNull(r.getHint(null));
        assertNull(r.getHint(""));
        assertNull(r.getHint("   "));
    }

    @Test
    public void hintedCallbackAppendsHintToDescription() {
        ToolDefinition orig = new StubDef("search_repositories", "Search GitHub repos", "{}");
        ToolCallback raw = new StubCallback(orig);
        HintedToolCallback hinted = new HintedToolCallback(raw, "Use per_page <= 10.");
        ToolDefinition out = hinted.getToolDefinition();
        assertEquals("search_repositories", out.name());
        assertTrue(out.description().startsWith("Search GitHub repos"));
        assertTrue(out.description().contains("Use per_page <= 10."));
        assertEquals("{}", out.inputSchema());
    }

    @Test
    public void hintedCallbackWithBlankHintIsZeroWrap() {
        ToolDefinition orig = new StubDef("any_tool", "desc", "{}");
        ToolCallback raw = new StubCallback(orig);
        HintedToolCallback hinted = new HintedToolCallback(raw, "");
        // hint 为空时直接返回原 definition，描述未被修改
        assertSame(orig, hinted.getToolDefinition());
    }

    @Test
    public void hintedCallbackPreservesCallDelegate() {
        ToolDefinition orig = new StubDef("tool_x", "d", "{}");
        StubCallback raw = new StubCallback(orig);
        raw.willReturn = "{\"ok\":true}";
        HintedToolCallback hinted = new HintedToolCallback(raw, "hint");
        assertEquals("{\"ok\":true}", hinted.call("{}"));
        assertEquals("{\"ok\":true}", hinted.call("{}", new ToolContext(java.util.Map.of())));
    }

    // ====== test fixtures ======

    private static class StubDef implements ToolDefinition {
        private final String n, d, s;
        StubDef(String n, String d, String s) { this.n = n; this.d = d; this.s = s; }
        @Override public String name() { return n; }
        @Override public String description() { return d; }
        @Override public String inputSchema() { return s; }
    }

    private static class StubCallback implements ToolCallback {
        private final ToolDefinition def;
        String willReturn = "";
        StubCallback(ToolDefinition def) { this.def = def; }
        @Override public ToolDefinition getToolDefinition() { return def; }
        @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
        @Override public String call(String toolInput) { return willReturn; }
        @Override public String call(String toolInput, ToolContext toolContext) { return willReturn; }
    }
}
