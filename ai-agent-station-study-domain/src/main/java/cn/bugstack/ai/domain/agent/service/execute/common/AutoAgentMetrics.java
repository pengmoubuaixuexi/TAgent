package cn.bugstack.ai.domain.agent.service.execute.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * P0-B2a/B2b-O1：Auto 状态机契约 shadow 指标采集（点分 Micrometer 名，低基数固定枚举 label）。
 *
 * <p>独立于 {@link LlmMetrics}/{@link McpToolMetrics}，不混入 LLM/MCP 维度。导出 Prometheus 时 Counter 自带 {@code _total}。
 * <ul>
 *   <li>{@code agent.auto.supervision.parse{phase,verdict}}：每次 Step3 裁决<b>解析尝试</b>（B2a 只有 phase=primary）；</li>
 *   <li>{@code agent.auto.analysis.completion{signal}}：每次 Step1 完成<b>解析尝试</b>；</li>
 *   <li>{@code agent.auto.contract.shadow{stage,legacy,candidate}}：candidate 与真实旧分支 legacy 的<b>对照</b>；</li>
 *   <li>{@code agent.auto.supervision.candidate{source}} / {@code agent.auto.analysis.candidate{source}}：candidate 来源 new_field|legacy_allowlist|none；</li>
 *   <li>{@code agent.auto.contract.fieldvsprose{stage,result}}：机器字段 vs 散文 allowlist agree|conflict|field_only|prose_only|both_none；</li>
 *   <li>{@code agent.auto.contract.unknown_reason{stage,reason}}：UNKNOWN 根因 empty|trailer_missing|required_missing|malformed|duplicate|unexpected|prose_conflict|field_prose_conflict|status_progress_conflict；</li>
 *   <li>{@code agent.auto.step.finish{stage,reason}}：末帧 finish reason stop|length|tool_calls|cancelled|unknown（用于区分截断 vs 正常收尾）。</li>
 * </ul>
 *
 * <p><b>label 纪律</b>：全部固定低基数枚举，<b>禁</b>原文 / agentId / sessionId 做 label。
 */
@Component
public class AutoAgentMetrics {

    private static final String METRIC_SUPERVISION_PARSE = "agent.auto.supervision.parse";
    private static final String METRIC_ANALYSIS_COMPLETION = "agent.auto.analysis.completion";
    private static final String METRIC_CONTRACT_SHADOW = "agent.auto.contract.shadow";
    private static final String METRIC_SUPERVISION_CANDIDATE = "agent.auto.supervision.candidate";
    private static final String METRIC_ANALYSIS_CANDIDATE = "agent.auto.analysis.candidate";
    private static final String METRIC_FIELD_VS_PROSE = "agent.auto.contract.fieldvsprose";
    private static final String METRIC_UNKNOWN_REASON = "agent.auto.contract.unknown_reason";
    private static final String METRIC_STEP_FINISH = "agent.auto.step.finish";

    private final MeterRegistry registry;

    public AutoAgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Step3 裁决解析尝试。
     *
     * @param phase   primary | repair（B2a/B2b-O1 仅 primary）
     * @param verdict pass | fail | optimize | unknown
     */
    public void recordSupervisionParse(String phase, String verdict) {
        registry.counter(METRIC_SUPERVISION_PARSE, "phase", lc(phase), "verdict", lc(verdict)).increment();
    }

    /**
     * Step1 完成解析尝试。
     *
     * @param signal completed | continue | unknown
     */
    public void recordAnalysisCompletion(String signal) {
        registry.counter(METRIC_ANALYSIS_COMPLETION, "signal", lc(signal)).increment();
    }

    /**
     * candidate vs 真实旧分支 legacy 对照。
     *
     * @param stage     step1 | step3
     * @param legacy    真实旧分支结果（固定枚举）
     * @param candidate 新 parser 结果（固定枚举）
     */
    public void recordContractShadow(String stage, String legacy, String candidate) {
        registry.counter(METRIC_CONTRACT_SHADOW, "stage", lc(stage), "legacy", lc(legacy), "candidate", lc(candidate))
                .increment();
    }

    // ====== B2b-O1：provenance / 诊断 ======

    /**
     * Step3 candidate 来源。
     *
     * @param source new_field | legacy_allowlist | none
     */
    public void recordSupervisionCandidateSource(String source) {
        registry.counter(METRIC_SUPERVISION_CANDIDATE, "source", lc(source)).increment();
    }

    /**
     * Step1 candidate 来源。
     *
     * @param source new_field | legacy_allowlist | none
     */
    public void recordAnalysisCandidateSource(String source) {
        registry.counter(METRIC_ANALYSIS_CANDIDATE, "source", lc(source)).increment();
    }

    /**
     * 机器字段 vs 散文 allowlist 交叉对照。
     *
     * @param stage  step1 | step3
     * @param result agree | conflict | field_only | prose_only | both_none
     */
    public void recordFieldVsProse(String stage, String result) {
        registry.counter(METRIC_FIELD_VS_PROSE, "stage", lc(stage), "result", lc(result)).increment();
    }

    /**
     * UNKNOWN 根因（仅当 resolved=UNKNOWN 记录）。
     *
     * @param stage  step1 | step3
     * @param reason empty|trailer_missing|required_missing|malformed|duplicate|unexpected|prose_conflict|field_prose_conflict|status_progress_conflict
     */
    public void recordUnknownReason(String stage, String reason) {
        registry.counter(METRIC_UNKNOWN_REASON, "stage", lc(stage), "reason", lc(reason)).increment();
    }

    /**
     * 末帧 finish reason（已由 {@link FinishReasonNormalizer} 归一）。
     *
     * @param stage  step1/step3，或真实 stepId step1_analyzer/step3_quality_supervisor；其他步骤忽略
     * @param reason stop | length | tool_calls | cancelled | unknown
     */
    public void recordFinishReason(String stage, String reason) {
        String normalizedStage;
        if ("step1".equals(stage) || "step1_analyzer".equals(stage)) {
            normalizedStage = "step1";
        } else if ("step3".equals(stage) || "step3_quality_supervisor".equals(stage)) {
            normalizedStage = "step3";
        } else {
            // B2b-O1 只诊断 Step1/Step3；不得把动态/其他 stepName 引入 label 基数。
            return;
        }
        registry.counter(METRIC_STEP_FINISH, "stage", normalizedStage,
                "reason", FinishReasonNormalizer.normalize(reason)).increment();
    }

    /**
     * Step3 contract repair 结果。
     *
     * @param outcome success | failure
     */
    public void recordSupervisionRepair(String outcome) {
        registry.counter("agent.auto.supervision.contract.repair", "outcome", lc(outcome)).increment();
    }

    /**
     * 质量交付终态（每 Auto 请求仅在 Step4 记一次，含 not_assessed）。
     *
     * @param status not_assessed | verified_pass | verified_fail | verified_optimize | quality_not_verified
     */
    public void recordQualityTerminal(String status) {
        registry.counter("agent.auto.quality.terminal", "status", lc(status)).increment();
    }

    private static String lc(String s) {
        return (s == null || s.isEmpty()) ? "unknown" : s.toLowerCase(Locale.ROOT);
    }
}
