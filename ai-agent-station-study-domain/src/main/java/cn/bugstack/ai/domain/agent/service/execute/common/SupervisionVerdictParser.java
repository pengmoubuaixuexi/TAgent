package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0-B2a：Step3 质检裁决的 <b>candidate</b> 解析器（shadow，用于"若按新契约会判成什么"）。
 *
 * <p><b>解析优先级</b>：
 * <ol>
 *   <li>新机器字段 {@code AUTO_QUALITY_VERDICT}（经 {@link ShadowContractTrailer#extract} 提取、本类只做合法性映射）；</li>
 *   <li>legacy allowlist（本类自有正则）：行级锚定 {@code 是否通过/审查结果/判定} + 裸独立行 {@code PASS|FAIL|OPTIMIZE}，
 *       全角/半角冒号归一、容许 markdown {@code #}/{@code **}；</li>
 *   <li>同值折叠为一个；异值冲突 / 空 / 无命中 → {@link Verdict#UNKNOWN}。</li>
 * </ol>
 *
 * <p><b>非升格</b>：自由正文里的「故判定为 FAIL」等 {@code contains} 形态不升格；{@code 结论：FAIL} 不在首版 allowlist。
 *
 * <p><b>重要（v3.md §43 约束 3）</b>：本 candidate <b>不是</b> shadow 比较里的 legacy 标签。
 * legacy 标签必须由调用方（Step3 节点）从<b>真实旧分支布尔</b>（{@code businessResult.contains("是否通过: FAIL")} 等）取得，
 * 不得用本类模拟旧控制流。
 *
 * <p>纯函数、零 Spring/DB。
 */
public final class SupervisionVerdictParser {

    private SupervisionVerdictParser() {
    }

    public enum Verdict {PASS, FAIL, OPTIMIZE, UNKNOWN}

    /** legacy allowlist：行首锚定 是否通过/审查结果/判定，冒号后接 PASS|FAIL|OPTIMIZE（容许 markdown 装饰与括注）。 */
    private static final Pattern LEGACY_ANCHORED = Pattern.compile(
            "(?i)^\\s*(?:#{1,6}\\s*)?(?:[-*>]\\s*)?(?:\\*\\*)?\\s*" +
                    "(?:是否通过|审查结果|判定)\\s*(?:\\*\\*)?\\s*[：:]\\s*(?:\\*\\*)?\\s*" +
                    "(PASS|FAIL|OPTIMIZE)\\s*(?:\\*\\*)?\\s*" +
                    "(?:[（(][^()（）\\r\\n]{0,40}[）)])?\\s*(?:✅|❌|⚠️|🔧)?\\s*[。.!！]?\\s*$");

    /** legacy 裸独立行：trim 后整行只有 PASS|FAIL|OPTIMIZE（容许两侧 markdown 装饰）。 */
    private static final Pattern LEGACY_BARE_LINE = Pattern.compile(
            "(?i)^\\s*(?:\\*\\*)?\\s*(PASS|FAIL|OPTIMIZE)\\s*(?:\\*\\*)?\\s*$");

    /**
     * 解析 candidate verdict。
     *
     * @param raw 模型原始输出（可能含尾部 {@code <!-- AUTO_QUALITY_VERDICT: ... -->}）
     */
    public static Verdict parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        // 1) 新机器字段优先（复用 ShadowContractTrailer，单一 grammar 权威）
        List<String> newValues = ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_QUALITY_VERDICT);
        if (ShadowContractTrailer.hasTrailer(raw)) {
            Map<String, String> fields = ShadowContractTrailer.extract(raw);
            if (newValues.size() != 1 || fields.size() != 1 ||
                    !fields.containsKey(ShadowContractTrailer.KEY_QUALITY_VERDICT)) {
                return Verdict.UNKNOWN;
            }
            Verdict fromNew = mapVerdict(newValues.get(0));
            return fromNew == null ? Verdict.UNKNOWN : fromNew;
        }
        // 2) legacy allowlist 跑在剥离 trailer 后的业务文本上
        String business = ShadowContractTrailer.withoutFencedCode(ShadowContractTrailer.strip(raw));
        return legacyVerdict(business);
    }

    /** 把 raw value 映射为合法 verdict；非法/缺失返回 null（由调用处落 UNKNOWN）。 */
    private static Verdict mapVerdict(String v) {
        if (v == null) {
            return null;
        }
        switch (v.trim().toUpperCase(Locale.ROOT)) {
            case "PASS":
                return Verdict.PASS;
            case "FAIL":
                return Verdict.FAIL;
            case "OPTIMIZE":
                return Verdict.OPTIMIZE;
            default:
                return null;
        }
    }

    /** legacy allowlist：收集所有命中，同值折叠，异值冲突→UNKNOWN，无命中→UNKNOWN。 */
    private static Verdict legacyVerdict(String business) {
        if (business == null || business.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        Verdict found = null;
        for (String line : business.split("\\n", -1)) {
            for (Pattern p : new Pattern[]{LEGACY_ANCHORED, LEGACY_BARE_LINE}) {
                Matcher m = p.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                Verdict v = mapVerdict(m.group(1));
                if (v == null) {
                    continue;
                }
                if (found == null) {
                    found = v;
                } else if (found != v) {
                    return Verdict.UNKNOWN; // 异值冲突
                }
                // 同值折叠：继续扫但不改变 found
            }
        }
        return found == null ? Verdict.UNKNOWN : found;
    }

    // ====== P0-B2b-O1：shadow provenance 自省（不改 parse() 行为；resolved==parse(raw) 保证字节一致） ======

    /** 机器字段状态。 */
    public enum FieldState {MISSING, VALID, INVALID, DUPLICATE, UNEXPECTED, REQUIRED_MISSING}

    /** 散文 allowlist 状态。 */
    public enum ProseState {NONE, VALID, CONFLICT}

    /**
     * 一次性给出「机器字段」与「散文」两路独立结果 + 冲突可见性 + UNKNOWN 根因。
     * {@code resolvedVerdict} 恒等于 {@link #parse(String)}，仅供 shadow 观察与 enforce 决策，B2b-O1 不改控制流。
     */
    public record Inspection(Verdict fieldVerdict, FieldState fieldState,
                             Verdict proseVerdict, ProseState proseState,
                             Verdict resolvedVerdict, String unknownReason) {
    }

    /** candidate 来源（供 {@code agent.auto.supervision.candidate{source}} 指标）。 */
    public static String candidateSource(Inspection i) {
        if (i.fieldState() == FieldState.VALID) {
            return "new_field";
        }
        if (i.fieldState() == FieldState.MISSING && i.proseState() == ProseState.VALID) {
            return "legacy_allowlist";
        }
        return "none";
    }

    /** field 与 prose 交叉对照（供 {@code agent.auto.supervision.fieldvsprose{result}} 指标）。 */
    public static String fieldVsProse(Inspection i) {
        // 任一路内部已经自相矛盾时，不能降格成 field_only/both_none；
        // conflict 是 B2b enforce 的硬阻断信号，必须在 shadow 指标中可见。
        if (i.proseState() == ProseState.CONFLICT) {
            return "conflict";
        }
        boolean f = i.fieldState() == FieldState.VALID;
        boolean p = i.proseState() == ProseState.VALID;
        if (f && p) {
            return i.fieldVerdict() == i.proseVerdict() ? "agree" : "conflict";
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
        Verdict resolved = parse(raw);

        Verdict fieldVerdict = Verdict.UNKNOWN;
        FieldState fieldState;
        if (raw == null || raw.isEmpty() || !ShadowContractTrailer.hasTrailer(raw)) {
            fieldState = FieldState.MISSING;
        } else {
            List<String> vals = ShadowContractTrailer.extractValues(raw, ShadowContractTrailer.KEY_QUALITY_VERDICT);
            Map<String, String> fields = ShadowContractTrailer.extract(raw);
            boolean hasExtra = fields.keySet().stream()
                    .anyMatch(k -> !ShadowContractTrailer.KEY_QUALITY_VERDICT.equals(k));
            if (vals.size() > 1) {
                fieldState = FieldState.DUPLICATE;
            } else if (vals.isEmpty()) {
                fieldState = FieldState.REQUIRED_MISSING;
            } else if (hasExtra) {
                fieldState = FieldState.UNEXPECTED;
            } else {
                Verdict mv = mapVerdict(vals.get(0));
                if (mv == null) {
                    fieldState = FieldState.INVALID;
                } else {
                    fieldState = FieldState.VALID;
                    fieldVerdict = mv;
                }
            }
        }

        String business = ShadowContractTrailer.withoutFencedCode(ShadowContractTrailer.strip(raw));
        Verdict proseVerdict = Verdict.UNKNOWN;
        ProseState proseState = ProseState.NONE;
        if (business != null && !business.isEmpty()) {
            Verdict found = null;
            boolean conflict = false;
            for (String line : business.split("\\n", -1)) {
                for (Pattern p : new Pattern[]{LEGACY_ANCHORED, LEGACY_BARE_LINE}) {
                    Matcher m = p.matcher(line);
                    if (!m.matches()) {
                        continue;
                    }
                    Verdict v = mapVerdict(m.group(1));
                    if (v == null) {
                        continue;
                    }
                    if (found == null) {
                        found = v;
                    } else if (found != v) {
                        conflict = true;
                    }
                }
            }
            if (conflict) {
                proseState = ProseState.CONFLICT;
            } else if (found != null) {
                proseState = ProseState.VALID;
                proseVerdict = found;
            }
        }

        String unknownReason = "none";
        if (resolved == Verdict.UNKNOWN) {
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
                    case MISSING:
                    default:
                        unknownReason = proseState == ProseState.CONFLICT
                                ? "prose_conflict" : "trailer_missing";
                        break;
                }
            }
        }

        return new Inspection(fieldVerdict, fieldState, proseVerdict, proseState, resolved, unknownReason);
    }

    /**
     * P0-B2b-Step3 enforce：把 inspection 收敛为「可执行裁决」。与 {@link #parse} 的迁移语义不同——
     * 这里把 field/prose 异值、prose 内部冲突、field 非 VALID 一律判 UNKNOWN（交 repair），实现 §51.5 组合规则。
     * <ul>
     *   <li>field VALID 且 prose NONE → field；</li>
     *   <li>field VALID 且 prose VALID 且同值 → field；</li>
     *   <li>其余全部 → UNKNOWN。</li>
     * </ul>
     */
    public static Verdict resolveForEnforcement(Inspection i) {
        if (i == null) {
            return Verdict.UNKNOWN;
        }
        if (i.fieldState() == FieldState.VALID) {
            if (i.proseState() == ProseState.NONE) {
                return i.fieldVerdict();
            }
            if (i.proseState() == ProseState.VALID && i.fieldVerdict() == i.proseVerdict()) {
                return i.fieldVerdict();
            }
        }
        return Verdict.UNKNOWN;
    }

    /**
     * B2b-Step3 enforce 的 UNKNOWN 根因。不能直接复用 {@link Inspection#unknownReason()}：
     * {@code parse()} 在合法 field 与 prose 冲突时仍按 field 返回非 UNKNOWN，而 enforce 会正确阻断；
     * 此时 inspection 的迁移期根因仍是 {@code none}。
     */
    public static String enforcementUnknownReason(Inspection i) {
        if (i == null) {
            return "empty";
        }
        if (resolveForEnforcement(i) != Verdict.UNKNOWN) {
            return "none";
        }
        if (i.fieldState() == FieldState.VALID) {
            return "field_prose_conflict";
        }
        if (i.fieldState() == FieldState.MISSING && i.proseState() == ProseState.CONFLICT) {
            return "prose_conflict";
        }
        String reason = i.unknownReason();
        return reason == null || reason.isBlank() || "none".equals(reason)
                ? "trailer_missing" : reason;
    }
}
