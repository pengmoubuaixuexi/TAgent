package cn.bugstack.ai.test.tool;

import cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-A2 ExecutorToolCatalog 骨架测试（v3.md §77）：从 ToolDefinition 派生 schema 快照 + name 级子集断言。
 * normalize 深化 / V1V2V3 快照点接入 / schema 级断言由 Codex 接力，本测试只锁骨架纯函数。
 */
public class ExecutorToolCatalogTest {

    private static ToolCallback cb(String name, String desc, String schema) {
        ToolCallback c = mock(ToolCallback.class);
        when(c.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name(name).description(desc).inputSchema(schema).build());
        return c;
    }

    @Test
    public void from_derivesSchemaEntriesFromToolDefinition() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(
                List.of(cb("calc", "计算器", "{\"type\":\"object\"}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        assertEquals(1, cat.snapshotVersion());
        ExecutorToolCatalog.Entry e = cat.entry("calc").orElseThrow();
        assertEquals("calc", e.exactName());
        assertEquals("计算器", e.hintedDescription());
        assertEquals("{\"type\":\"object\"}", e.inputSchemaJson());
        assertEquals("type=object", e.normalizedInputSchema());
        assertFalse("sha256 hash 非空", e.schemaHash().isBlank());
        assertEquals(ExecutorToolCatalog.Source.DYNAMIC, e.source());
    }

    @Test
    public void containsAll_and_missingFrom() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(
                List.of(cb("a", "", "{}"), cb("b", "", "{}")), ExecutorToolCatalog.Source.RESIDENT, 2);
        assertTrue(cat.containsAll(List.of("a", "b")));
        assertFalse("计划工具 c 不在快照 → 子集断言失败", cat.containsAll(List.of("a", "c")));
        assertEquals(List.of("c"), cat.missingFrom(List.of("a", "c")));
        assertTrue(cat.missingFrom(List.of("a", "b")).isEmpty());
    }

    @Test
    public void from_dedupsByNameKeepsFirst() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(
                List.of(cb("dup", "first", "{}"), cb("dup", "second", "{}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        assertEquals(1, cat.toolNames().size());
        assertEquals("first", cat.entry("dup").orElseThrow().hintedDescription());
    }

    @Test
    public void from_nullCallbacks_emptyCatalogContainsAllVacuouslyTrue() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(null, ExecutorToolCatalog.Source.RESIDENT, 0);
        assertTrue(cat.toolNames().isEmpty());
        assertTrue(cat.containsAll(null));
        assertTrue(cat.containsAll(List.of()));
    }

    @Test
    public void schemaHash_stableForSameSchema_changesForDifferent() {
        ExecutorToolCatalog c1 = ExecutorToolCatalog.from(List.of(cb("x", "", "{\"a\":1}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        ExecutorToolCatalog c2 = ExecutorToolCatalog.from(List.of(cb("x", "", "{\"a\":1}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        ExecutorToolCatalog c3 = ExecutorToolCatalog.from(List.of(cb("x", "", "{\"a\":2}")), ExecutorToolCatalog.Source.DYNAMIC, 1);
        assertEquals(c1.entry("x").orElseThrow().schemaHash(), c2.entry("x").orElseThrow().schemaHash());
        assertNotEquals(c1.entry("x").orElseThrow().schemaHash(), c3.entry("x").orElseThrow().schemaHash());
    }
    @Test
    public void from_residentPlusDynamic_keepsResidentFirstAndTagsSource() {
        ExecutorToolCatalog cat = ExecutorToolCatalog.from(
                List.of(cb("base", "resident", "{\"type\":\"object\"}"), cb("dup", "resident dup", "{}")),
                List.of(cb("dyn", "dynamic", "{\"type\":\"object\"}"), cb("dup", "dynamic dup", "{\"type\":\"string\"}")),
                3);

        assertEquals(List.of("base", "dup", "dyn"), cat.toolNames().stream().toList());
        assertEquals(ExecutorToolCatalog.Source.RESIDENT, cat.entry("dup").orElseThrow().source());
        assertEquals("resident dup", cat.entry("dup").orElseThrow().hintedDescription());
        assertEquals(ExecutorToolCatalog.Source.DYNAMIC, cat.entry("dyn").orElseThrow().source());
    }

    @Test
    public void normalizedSchema_extractsRequiredPropertiesEnumsAndConstraintsDeterministically() {
        String schema = """
                {\"type\":\"object\",\"required\":[\"q\",\"mode\"],\"properties\":{
                  \"mode\":{\"type\":\"string\",\"enum\":[\"fast\",\"deep\"]},
                  \"q\":{\"type\":\"string\",\"description\":\"query\",\"minLength\":1}
                }}
                """;

        String normalized = ExecutorToolCatalog.normalizeInputSchema(schema);

        assertTrue(normalized.contains("type=object"));
        assertTrue(normalized.contains("required=mode,q"));
        assertTrue(normalized.contains("property.mode=type=string;enum=deep|fast"));
        assertTrue(normalized.contains("property.q=type=string;description=query;minLength=1"));
    }

    @Test
    public void missingOrChangedFrom_detectsLineageDrift() {
        ExecutorToolCatalog v1 = ExecutorToolCatalog.from(
                List.of(cb("stable", "", "{\"type\":\"object\"}"), cb("gone", "", "{}"), cb("changed", "", "{\"a\":1}")),
                ExecutorToolCatalog.Source.RESIDENT, 1);
        ExecutorToolCatalog v2 = ExecutorToolCatalog.from(
                List.of(cb("stable", "", "{\"type\":\"object\"}"), cb("changed", "", "{\"a\":2}"), cb("new", "", "{}")),
                ExecutorToolCatalog.Source.RESIDENT, 2);

        assertEquals(List.of("gone:missing", "changed:schema_changed"), v2.missingOrChangedFrom(v1));
        assertTrue(v2.missingOrChangedFrom(null).isEmpty());
    }
}
