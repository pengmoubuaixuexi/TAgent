package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0-B2a：Step1 完成判定的 <b>candidate</b> 解析器（shadow，用于"若按新契约会判成什么"）。
 *
 * <p><b>解析优先级</b>：
 * <ol>
 *   <li>新机器字段 {@code AUTO_COMPLETION_STATUS}（CONTINUE/COMPLETED）+ {@code AUTO_COMPLETION_PROGRESS}（0..100 整数 %），
 *       经 {@link ShadowContractTrailer#extract} 提取、本类只做合法性映射与一致性判定；</li>
 *   <li>legacy：精确 {@code 任务状态: COMPLETED} / {@code 完成度评估: 100%}（仅完整字段值 100，{@code 1000%}/{@code 100%以外} 不命中），
 *       全角/半角冒号归一；</li>
 *   <li>两字段同向→对应结果；相互冲突 / 越界 / 缺失全部→ {@link Signal#UNKNOWN}；缺一可用唯一有效字段。</li>
 * </ol>
 *
 * <p><b>重要（v3.md §43 约束 3）</b>：本 candidate <b>不是</b> shadow 比较里的 legacy 标签。
 * legacy 标签由 Step1 节点从真实旧分支布尔（{@code contains("任务状态: COMPLETED")||contains("完成度评估: 100%")}）取得。
 *
 * <p>纯函数、零 Spring/DB。
 */
public final class AnalysisCompletionDetector {

    private AnalysisCompletionDetector() {
    }

    public enum Signal {COMPLETED, CONTINUE, UNKNOWN}

    /** legacy：任务状态行，冒号后 COMPLETED|CONTINUE|IN_PROGRESS|BLOCKED。 */
    private static final Pattern LEGACY_STATUS = Pattern.compile(
            "(?i)^\\s*(?:#{1,6}\\s*)?(?:[-*>]\\s*)?(?:\\*\\*)?\\s*任务状态\\s*(?:\\*\\*)?\\s*[：:]\\s*" +
                    "(?:\\*\\*)?\\s*(COMPLETED|CONTINUE|IN_PROGRESS|BLOCKED)\\s*(?:\\*\\*)?\\s*" +
                    "(?:[（(][^()（）\\r\\n]{0,40}[）)])?\\s*[。.!！]?\\s*$");

    /** legacy：完成度评估行，精确整百分比（完整字段值 0..100）。 */
    private static final Pattern LEGACY_PROGRESS = Pattern.compile(
            "(?i)^\\s*(?:#{1,6}\\s*)?(?:[-*>]\\s*)?(?:\\*\\*)?\\s*完成度评估\\s*(?:\\*\\*)?\\s*[：:]\\s*" +
                    "(?:\\*\\*)?\\s*(\\d{1,3})\\s*[％%]\\s*(?:\\*\\*)?\\s*" +
                    "(?:[（(][^()（）\\r\\n]{0,40}[）)])?\\s*[。.!！]?\\s*$");

    /**
     * 解析 candidate completion signal。
     *
     * @param raw 模型原始输出（可能含尾部 {@code <!-- AUTO_COMPLETION_* -->}）
     */
    public static Signal parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Signal.UNKNOWN;
        }
        // 1) 新机器字段优先
        List<String> statusValues = ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_COMPLETION_STATUS);
        List<String> progressValues = ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_COMPLETION_PROGRESS);
        if (ShadowContractTrailer.hasTrailer(raw)) {
            Map<String, String> fields = ShadowContractTrailer.extract(raw);
            boolean unexpectedField = fields.keySet().stream().anyMatch(k ->
                    !ShadowContractTrailer.KEY_COMPLETION_STATUS.equals(k) &&
                            !ShadowContractTrailer.KEY_COMPLETION_PROGRESS.equals(k));
            if (statusValues.size() > 1 || progressValues.size() > 1 ||
                    (statusValues.isEmpty() && progressValues.isEmpty()) || unexpectedField) {
                return Signal.UNKNOWN;
            }
            String statusVal = statusValues.isEmpty() ? null : statusValues.get(0);
            String progressVal = progressValues.isEmpty() ? null : progressValues.get(0);
            Boolean statusCompleted = mapStatus(statusVal);
            Boolean progressCompleted = mapProgress(progressVal);
            if ((!statusValues.isEmpty() && statusCompleted == null) ||
                    (!progressValues.isEmpty() && progressCompleted == null)) {
                return Signal.UNKNOWN;
            }
            return combine(statusCompleted, progressCompleted);
        }
        // 2) legacy 跑在剥离 trailer 后的业务文本上
        String business = ShadowContractTrailer.withoutFencedCode(ShadowContractTrailer.strip(raw));
        return legacySignal(business);
    }

    /** CONTINUE/COMPLETED → 布尔；非法/缺失 → null。 */
    private static Boolean mapStatus(String v) {
        if (v == null) {
            return null;
        }
        switch (v.trim().toUpperCase(Locale.ROOT)) {
            case "COMPLETED":
                return Boolean.TRUE;
            case "CONTINUE":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** 0..100 整数 → 是否 100；非法/越界/缺失 → null。 */
    private static Boolean mapProgress(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.endsWith("%") || t.endsWith("％")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if (!t.matches("\\d{1,3}")) {
            return null;
        }
        int n = Integer.parseInt(t);
        if (n < 0 || n > 100) {
            return null;
        }
        return n == 100;
    }

    /** 两字段合并：同向→结果；冲突→UNKNOWN；都缺→null（交给 legacy）。 */
    private static Signal combine(Boolean status, Boolean progress) {
        if (status == null && progress == null) {
            return null;
        }
        if (status != null && progress != null) {
            if (status.booleanValue() != progress.booleanValue()) {
                return Signal.UNKNOWN; // 冲突
            }
            return status ? Signal.COMPLETED : Signal.CONTINUE;
        }
        Boolean only = status != null ? status : progress;
        return only ? Signal.COMPLETED : Signal.CONTINUE;
    }

    /** legacy：旧两 contains 的精确等价（任务状态: COMPLETED / 完成度评估: 100%）。 */
    private static Signal legacySignal(String business) {
        if (business == null || business.isEmpty()) {
            return Signal.UNKNOWN;
        }
        Boolean statusCompleted = null;
        Boolean progressCompleted = null;
        for (String line : business.split("\\n", -1)) {
            Matcher ms = LEGACY_STATUS.matcher(line);
            if (ms.matches()) {
                boolean c = "COMPLETED".equalsIgnoreCase(ms.group(1));
                if (statusCompleted == null) {
                    statusCompleted = c;
                } else if (statusCompleted != c) {
                    return Signal.UNKNOWN;
                }
            }
            Matcher mp = LEGACY_PROGRESS.matcher(line);
            if (mp.matches()) {
                int progress = Integer.parseInt(mp.group(1));
                if (progress > 100) {
                    return Signal.UNKNOWN;
                }
                boolean c = progress == 100;
                if (progressCompleted == null) {
                    progressCompleted = c;
                } else if (progressCompleted != c) {
                    return Signal.UNKNOWN;
                }
            }
        }
        Signal s = combine(statusCompleted, progressCompleted);
        return s == null ? Signal.UNKNOWN : s;
    }

    // ====== P0-B2b-O1：shadow provenance 自省（不改 parse() 行为；resolved==parse(raw) 保证字节一致） ======

    /** 机器字段状态。{@code FIELD_CONFLICT}=status 与 progress 均合法但相互矛盾（status_progress_conflict）。 */
    public enum FieldState {MISSING, VALID, INVALID, DUPLICATE, UNEXPECTED, REQUIRED_MISSING, FIELD_CONFLICT}

    /** 散文 legacy 状态。 */
    public enum ProseState {NONE, VALID, CONFLICT}

    public record Inspection(Signal fieldSignal, FieldState fieldState,
                             Signal proseSignal, ProseState proseState,
                             Signal resolvedSignal, String unknownReason) {
    }

    /** candidate 来源（供 {@code agent.auto.analysis.candidate{source}} 指标）。 */
    public static String candidateSource(Inspection i) {
        if (i.fieldState() == FieldState.VALID) {
            return "new_field";
        }
        if (i.fieldState() == FieldState.MISSING && i.proseState() == ProseState.VALID) {
            return "legacy_allowlist";
        }
        return "none";
    }

    /** field 与 prose 交叉对照（供 {@code agent.auto.analysis.fieldvsprose{result}} 指标）。 */
    public static String fieldVsProse(Inspection i) {
        // status/progress 自相矛盾，或散文内部自相矛盾，都属于 conflict；
        // 不能误记成 both_none/field_only，否则会掩盖 enforce 的硬阻断信号。
        if (i.fieldState() == FieldState.FIELD_CONFLICT || i.proseState() == ProseState.CONFLICT) {
            return "conflict";
        }
        boolean f = i.fieldState() == FieldState.VALID;
        boolean p = i.proseState() == ProseState.VALID;
        if (f && p) {
            return i.fieldSignal() == i.proseSignal() ? "agree" : "conflict";
        }
        if (f) {
            return "field_only";
        }
        if (p) {
            return "prose_only";
        }
        return "both_none";
    }

    public static Inspection inspect(String raw) {
        Signal resolved = parse(raw);

        Signal fieldSignal = Signal.UNKNOWN;
        FieldState fieldState;
        if (raw == null || raw.isEmpty() || !ShadowContractTrailer.hasTrailer(raw)) {
            fieldState = FieldState.MISSING;
        } else {
            List<String> statusValues = ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_COMPLETION_STATUS);
            List<String> progressValues = ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_COMPLETION_PROGRESS);
            Map<String, String> fields = ShadowContractTrailer.extract(raw);
            boolean hasExtra = fields.keySet().stream().anyMatch(k ->
                    !ShadowContractTrailer.KEY_COMPLETION_STATUS.equals(k) &&
                            !ShadowContractTrailer.KEY_COMPLETION_PROGRESS.equals(k));
            if (statusValues.size() > 1 || progressValues.size() > 1) {
                fieldState = FieldState.DUPLICATE;
            } else if (statusValues.isEmpty() && progressValues.isEmpty()) {
                fieldState = FieldState.REQUIRED_MISSING;
            } else if (hasExtra) {
                fieldState = FieldState.UNEXPECTED;
            } else {
                Boolean statusB = mapStatus(statusValues.isEmpty() ? null : statusValues.get(0));
                Boolean progressB = mapProgress(progressValues.isEmpty() ? null : progressValues.get(0));
                if ((!statusValues.isEmpty() && statusB == null) ||
                        (!progressValues.isEmpty() && progressB == null)) {
                    fieldState = FieldState.INVALID;
                } else {
                    Signal combined = combine(statusB, progressB);
                    if (combined == Signal.UNKNOWN) {
                        fieldState = FieldState.FIELD_CONFLICT;
                    } else {
                        fieldState = FieldState.VALID;
                        fieldSignal = combined;
                    }
                }
            }
        }

        String business = ShadowContractTrailer.withoutFencedCode(ShadowContractTrailer.strip(raw));
        Signal proseSignal = Signal.UNKNOWN;
        ProseState proseState = ProseState.NONE;
        if (business != null && !business.isEmpty()) {
            Boolean statusCompleted = null;
            Boolean progressCompleted = null;
            boolean matched = false;
            boolean conflict = false;
            for (String line : business.split("\\n", -1)) {
                Matcher ms = LEGACY_STATUS.matcher(line);
                if (ms.matches()) {
                    matched = true;
                    boolean c = "COMPLETED".equalsIgnoreCase(ms.group(1));
                    if (statusCompleted == null) {
                        statusCompleted = c;
                    } else if (statusCompleted != c) {
                        conflict = true;
                    }
                }
                Matcher mp = LEGACY_PROGRESS.matcher(line);
                if (mp.matches()) {
                    matched = true;
                    int progress = Integer.parseInt(mp.group(1));
                    if (progress > 100) {
                        conflict = true;
                    } else {
                        boolean c = progress == 100;
                        if (progressCompleted == null) {
                            progressCompleted = c;
                        } else if (progressCompleted != c) {
                            conflict = true;
                        }
                    }
                }
            }
            Signal combined = conflict ? Signal.UNKNOWN : combine(statusCompleted, progressCompleted);
            if (conflict || combined == Signal.UNKNOWN) {
                proseState = matched ? ProseState.CONFLICT : ProseState.NONE;
            } else if (combined != null) {
                proseState = ProseState.VALID;
                proseSignal = combined;
            }
        }

        String unknownReason = "none";
        if (resolved == Signal.UNKNOWN) {
            if (raw == null || raw.isBlank()) {
                unknownReason = "empty";
            } else {
                switch (fieldState) {
                    case DUPLICATE:
                        unknownReason = "duplicate";
                        break;
                    case REQUIRED_MISSING:
                        unknownReason = "required_missing";
                        break;
                    case UNEXPECTED:
                        unknownReason = "unexpected";
                        break;
                    case INVALID:
                        unknownReason = "malformed";
                        break;
                    case FIELD_CONFLICT:
                        unknownReason = "status_progress_conflict";
                        break;
                    case MISSING:
                    default:
                        unknownReason = proseState == ProseState.CONFLICT
                                ? "prose_conflict" : "trailer_missing";
                        break;
                }
            }
        }

        return new Inspection(fieldSignal, fieldState, proseSignal, proseState, resolved, unknownReason);
    }
}
