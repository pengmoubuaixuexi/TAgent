package cn.bugstack.ai.trigger.eval;

import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class EvalCaseConfig {
    private Set<String> expectedCapabilities = new LinkedHashSet<>();
    private Set<String> mustMention = new LinkedHashSet<>();
    private Set<String> mustNotMention = new LinkedHashSet<>();
    private boolean allowGeneralFallback = true;
    private boolean expectRag;
    private boolean expectMemory;
    private boolean expectTools;
    private boolean allowTools;
    private boolean simpleTask;
    private boolean financialSafety;
    private int maxSteps = 4;
    /** 可选的题目级最短答案要求；0 表示不使用长度规则。 */
    private int minAnswerLength;
    /** 性能目标线：用于效率评分和超预算记录，不会在此时间点中断任务。 */
    private long maxLatencyMs = 180_000L;

    public EvalCaseConfig normalized() {
        if (expectedCapabilities == null) expectedCapabilities = new LinkedHashSet<>();
        if (mustMention == null) mustMention = new LinkedHashSet<>();
        if (mustNotMention == null) mustNotMention = new LinkedHashSet<>();
        if (expectedCapabilities.isEmpty()) expectedCapabilities.add("general");
        maxSteps = Math.max(1, Math.min(maxSteps, 50));
        minAnswerLength = Math.max(0, Math.min(minAnswerLength, 10_000));
        maxLatencyMs = Math.max(5_000L, Math.min(maxLatencyMs, 1_800_000L));
        if (expectTools) allowTools = true;
        return this;
    }
}
