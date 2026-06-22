package cn.bugstack.ai.domain.agent.service.execute.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0-B2a：机器可读 shadow 契约尾注（HTML 注释形态）的<b>单一 grammar 权威</b>。
 *
 * <p>负责对模型原始输出做三件事，且仅此一处持有 {@code AUTO_*} 正则（{@code SupervisionVerdictParser} /
 * {@code AnalysisCompletionDetector} 复用本类结果，不得各自再复制 {@code AUTO_*} 正则——见 v3.md §43 约束 2）：
 * <ol>
 *   <li>{@link #extract(String)}：提取<b>尾部</b>、非代码围栏内、保留命名空间 {@code <!-- AUTO_*: value -->} 的原始 value；</li>
 *   <li>{@link #strip(String)}：把这些尾部 trailer 行从业务文本中剥离，得到 businessResult；</li>
 * </ol>
 *
 * <p><b>strip 与 extract 的有效性边界分离（v3.md §43 约束 1）</b>：
 * 只要是<b>保留命名空间、位于尾部、不在 fenced code 内</b>的 {@code <!-- AUTO_*: ... -->}，{@link #strip} 就剥离，
 * <b>即使 value 非法</b>（如占位符未替换 {@code VERDICT}/{@code N%}）——否则非法机器行仍会污染 Step2/历史/SSE。
 * 而 value 的<b>合法性校验由各 parser 负责</b>：parser 只有在 value 合法时才产出 candidate，否则记 UNKNOWN。
 *
 * <p><b>负例（绝不误删/误提）</b>：正文里提到 {@code AUTO_QUALITY_VERDICT} 的普通文字（非 {@code <!-- -->} 注释行）、
 * 非尾部位置的相似行、位于未闭合代码围栏内的尾部相似行。
 *
 * <p>纯函数、零 Spring/DB，可直接单测。null/blank 安全。
 */
public final class ShadowContractTrailer {

    private ShadowContractTrailer() {
    }

    /** 单行 shadow 尾注：{@code <!-- AUTO_XXX: value -->}（整行匹配，前后允许空白）。 */
    private static final Pattern TRAILER_LINE = Pattern.compile(
            "^\\s*<!--\\s*(AUTO_[A-Z0-9_]+)\\s*:\\s*(.*?)\\s*-->\\s*$");

    /** markdown 代码围栏行（反引号或波浪线，至少 3 个）。 */
    private static final Pattern FENCE_LINE = Pattern.compile("^\\s*(`{3,}|~{3,})");

    /** Step3 质检裁决机器字段 key。 */
    public static final String KEY_QUALITY_VERDICT = "AUTO_QUALITY_VERDICT";
    /** Step1 完成度机器字段 key。 */
    public static final String KEY_COMPLETION_PROGRESS = "AUTO_COMPLETION_PROGRESS";
    /** Step1 完成状态机器字段 key。 */
    public static final String KEY_COMPLETION_STATUS = "AUTO_COMPLETION_STATUS";

    /**
     * 定位「尾部 trailer 块」：从文末向上跳过空行，连续吃 (空行 | trailer 行)，
     * 返回最顶端 trailer 行的下标；无尾部 trailer 返回 -1。
     * 若该块上方代码围栏数为奇数（说明尾部其实在未闭合 fence 内），按非 trailer 处理，返回 -1。
     */
    private static int tailTrailerStart(String[] lines) {
        int i = lines.length - 1;
        int firstTrailer = -1;
        while (i >= 0) {
            String line = lines[i];
            if (TRAILER_LINE.matcher(line).matches()) {
                firstTrailer = i;
                i--;
            } else if (line.trim().isEmpty()) {
                i--;
            } else {
                break;
            }
        }
        if (firstTrailer == -1) {
            return -1;
        }
        // fenced-code 防误删：跟踪同类围栏的开/关，尾部若仍处于 fence 内则不当作 trailer
        Character openFence = null;
        for (int k = 0; k < firstTrailer; k++) {
            Matcher fence = FENCE_LINE.matcher(lines[k]);
            if (fence.find()) {
                char marker = fence.group(1).charAt(0);
                if (openFence == null) {
                    openFence = marker;
                } else if (openFence == marker) {
                    openFence = null;
                }
            }
        }
        if (openFence != null) {
            return -1;
        }
        return firstTrailer;
    }

    /**
     * 剥离尾部 shadow trailer，返回业务文本。null→null，无尾部 trailer→原样返回（去掉尾随空白）。
     */
    public static String strip(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String[] lines = raw.split("\n", -1);
        int start = tailTrailerStart(lines);
        if (start < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < start; k++) {
            if (k > 0) {
                sb.append('\n');
            }
            sb.append(lines[k]);
        }
        // 去掉尾部 trailer 留下的尾随空白/换行
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) {
            end--;
        }
        return sb.substring(0, end);
    }

    /**
     * 提取尾部 trailer 的原始 value（key→raw value）。同 key 多次出现取<b>最后一个</b>。
     * 不做合法性校验（合法性由各 parser 负责）。无尾部 trailer→空 map。
     */
    public static Map<String, String> extract(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String[] lines = raw.split("\n", -1);
        int start = tailTrailerStart(lines);
        if (start < 0) {
            return out;
        }
        for (int k = start; k < lines.length; k++) {
            Matcher m = TRAILER_LINE.matcher(lines[k]);
            if (m.matches()) {
                out.put(m.group(1), m.group(2).trim());
            }
        }
        return out;
    }

    /** 是否存在一个合规位置的尾部 shadow trailer 块（值可以非法）。 */
    public static boolean hasTrailer(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return tailTrailerStart(raw.split("\n", -1)) >= 0;
    }

    /**
     * 提取指定 key 在尾部 trailer 块中的全部原始值，保留出现顺序。
     * parser 用它识别重复字段；权威字段重复（即使同值）属于契约违规，不得悄悄取最后一个。
     */
    public static List<String> extractValues(String raw, String key) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isEmpty() || key == null) {
            return values;
        }
        String[] lines = raw.split("\n", -1);
        int start = tailTrailerStart(lines);
        if (start < 0) {
            return values;
        }
        for (int k = start; k < lines.length; k++) {
            Matcher m = TRAILER_LINE.matcher(lines[k]);
            if (m.matches() && key.equals(m.group(1))) {
                values.add(m.group(2).trim());
            }
        }
        return values;
    }

    /**
     * 删除 Markdown fenced-code 内容后返回同等行结构的文本，供 legacy 行解析器避免把示例代码升格为裁决。
     */
    public static String withoutFencedCode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        Character openFence = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher fence = FENCE_LINE.matcher(lines[i]);
            if (fence.find()) {
                char marker = fence.group(1).charAt(0);
                if (openFence == null) {
                    openFence = marker;
                } else if (openFence == marker) {
                    openFence = null;
                }
            } else if (openFence == null) {
                out.append(lines[i]);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }
}
