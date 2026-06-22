package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.execute.flow.step.Step3ParseStepsNode;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P0-B1 特征化测试（真代码）：Flow 规划步骤解析器 {@code Step3ParseStepsNode}。
 *
 * <p>锁定 §3 契约表第 3/4 行：
 * <ul>
 *   <li><b>HARD</b> {@code 第(\d+)步} —— 解析不到步=DAG 空（无执行步）；</li>
 *   <li><b>FALLBACK</b> {@code DEPENDS_ON: NONE|1,2,3} + 中文 {@code 依赖步骤:} —— 缺失=默认无依赖。</li>
 * </ul>
 *
 * <p><b>为什么是真代码</b>：{@code parseExecutionSteps(String)} / {@code parseStepDependencies(Map)}
 * 是私有<b>纯</b>方法（只用入参 + 静态 {@code Pattern}，不读任何注入字段）。其基类链
 * {@code AbstractExecuteSupport → AbstractMultiThreadStrategyRouter} 的无参构造经字节码确认仅
 * {@code Object.<init>()} + 设默认 handler，无线程池/无重初始化 → {@code new Step3ParseStepsNode()}
 * 安全；用反射 {@code setAccessible} 调这两个私有方法，断言落在真实生产解析逻辑上。
 *
 * <p>纯 JUnit4，零 Spring/LLM/DB。输入均为合成、脱敏字符串。
 */
public class FlowDagParserContractTest {

    // ---- 反射桥（私有纯方法）----

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseSteps(String planning) throws Exception {
        Step3ParseStepsNode node = new Step3ParseStepsNode();
        Method m = Step3ParseStepsNode.class.getDeclaredMethod("parseExecutionSteps", String.class);
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(node, planning);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Set<Integer>> parseDeps(Map<String, String> stepsMap) throws Exception {
        Step3ParseStepsNode node = new Step3ParseStepsNode();
        Method m = Step3ParseStepsNode.class.getDeclaredMethod("parseStepDependencies", Map.class);
        m.setAccessible(true);
        return (Map<Integer, Set<Integer>>) m.invoke(node, stepsMap);
    }

    // ===================================================================
    // parseExecutionSteps —— 三种格式（§3 HARD 第(\d+)步）
    // ===================================================================

    /** 策略1：### header 详情块。 */
    @Test
    public void parsesHashHeaderFormat() throws Exception {
        String planning = "### 第1步：搜集资料\n详情A\n### 第2步：整理\n详情B";
        Map<String, String> steps = parseSteps(planning);
        assertEquals(2, steps.size());
        assertTrue(steps.containsKey("第1步"));
        assertTrue(steps.containsKey("第2步"));
        assertTrue("body 应并入 value", steps.get("第1步").contains("详情A"));
    }

    /** 策略2：行首锚定的 plain "第N步：" 详情块（不会误吃 "[ ] 第N步" 概览行）。 */
    @Test
    public void parsesPlainHeaderFormat() throws Exception {
        String planning = "第1步：搜集\nDEPENDS_ON: NONE\n第2步：整理\nDEPENDS_ON: 1";
        Map<String, String> steps = parseSteps(planning);
        assertEquals(2, steps.size());
        assertTrue(steps.containsKey("第1步"));
        assertTrue(steps.containsKey("第2步"));
        assertTrue("plain 格式 body 含依赖行", steps.get("第2步").contains("DEPENDS_ON: 1"));
    }

    /** 策略3 兜底：仅 checkbox 概览（无 body）。 */
    @Test
    public void fallsBackToCheckboxOverviewOnly() throws Exception {
        String planning = "[ ] 第1步：A\n[ ] 第2步：B";
        Map<String, String> steps = parseSteps(planning);
        assertEquals(2, steps.size());
        assertTrue(steps.containsKey("第1步"));
        assertTrue(steps.containsKey("第2步"));
    }

    /** 解析不到 "第N步" → 空 map（DAG 退化为无执行步）。 */
    @Test
    public void emptyOrUnmatchedYieldsEmptyMap() throws Exception {
        assertEquals(0, parseSteps("").size());
        assertEquals(0, parseSteps("一段没有任何步骤标记的纯文本").size());
    }

    // ===================================================================
    // parseStepDependencies —— DEPENDS_ON 主格式 + 中文兼容（§3 FALLBACK）
    // ===================================================================

    private static Map<String, String> steps(String... titleAndBody) {
        // 入参成对：key, value, key, value...
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < titleAndBody.length; i += 2) {
            m.put(titleAndBody[i], titleAndBody[i + 1]);
        }
        return m;
    }

    @Test
    public void dependsOnNoneIsEmpty() throws Exception {
        Map<Integer, Set<Integer>> deps = parseDeps(steps("第1步", "第1步：A\nDEPENDS_ON: NONE"));
        assertTrue(deps.get(1).isEmpty());
    }

    @Test
    public void dependsOnSingle() throws Exception {
        Map<Integer, Set<Integer>> deps = parseDeps(steps("第2步", "第2步：B\nDEPENDS_ON: 1"));
        assertEquals(1, deps.get(2).size());
        assertTrue(deps.get(2).contains(1));
    }

    @Test
    public void dependsOnMultiple() throws Exception {
        Map<Integer, Set<Integer>> deps = parseDeps(steps("第3步", "第3步：C\nDEPENDS_ON: 1,2"));
        assertEquals(2, deps.get(3).size());
        assertTrue(deps.get(3).contains(1));
        assertTrue(deps.get(3).contains(2));
    }

    /** 中文老格式兼容："依赖步骤: 第1步"。 */
    @Test
    public void legacyChineseDependency() throws Exception {
        Map<Integer, Set<Integer>> deps = parseDeps(steps("第2步", "第2步：B\n依赖步骤: 第1步"));
        assertTrue(deps.get(2).contains(1));
    }

    /** 自引用排除：DEPENDS_ON 含自身步号时被 {@code n != currentStep} 滤掉。 */
    @Test
    public void excludesSelfReference() throws Exception {
        Map<Integer, Set<Integer>> deps = parseDeps(steps("第2步", "第2步：B\nDEPENDS_ON: 2,1"));
        assertEquals("自身 2 应被排除，只剩 1", 1, deps.get(2).size());
        assertTrue(deps.get(2).contains(1));
    }

    /** 无依赖行 → 默认无依赖（空 set）。 */
    @Test
    public void noDependencyLineDefaultsEmpty() throws Exception {
        Map<Integer, Set<Integer>> deps = parseDeps(steps("第1步", "第1步：A\n（没有写任何依赖行）"));
        assertTrue(deps.get(1).isEmpty());
    }

    /** 空 stepsMap → 空依赖 map。 */
    @Test
    public void emptyStepsYieldsEmptyDeps() throws Exception {
        assertEquals(0, parseDeps(new LinkedHashMap<>()).size());
    }
}
