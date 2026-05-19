package cn.bugstack.ai.domain.agent.service.rag.splitter;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义感知切片器。
 * <p>
 * 分四级降级切：
 * <ul>
 *   <li>① Markdown 标题 (# ## ###...) → 章节</li>
 *   <li>② 段落（空行分隔）</li>
 *   <li>③ 句末标点（。！？.!?）→ 句子</li>
 *   <li>④ 字符硬切（极长 URL / 长代码硬顶）</li>
 * </ul>
 * <p>
 * 针对中文技术文档做了三处专门处理：
 * <ul>
 *   <li><b>代码块原子化</b>：被 ```…``` / ~~~…~~~ 包裹的内容整体成 chunk，永不在内部切；
 *       方法调用里到处是点号，否则会被句子层切成碎片。</li>
 *   <li><b>剥离图片引用</b>：Markdown 的 ![alt](path) 对语义检索无价值，只占字符预算，预处理时删除。</li>
 *   <li><b>overlap 就近落句号</b>：取末尾 N 字符时向后找第一个句末标点，避免在句子中间切断。</li>
 * </ul>
 * <p>
 * 单位全部是字符数：中文 1 char 近似 1.5 token，用 char 做阈值直观且不依赖 tokenizer。
 */
public class SemanticTextSplitter extends TextSplitter {

    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s+.+$");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?\\.])\\s*");
    /** Markdown 图片引用：![alt](path or url)。不 greedy，避免吃掉后面的正文 */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    /** 代码块围栏 ``` 或 ~~~，独占一行 */
    private static final Pattern CODE_FENCE = Pattern.compile("^(```|~~~)");

    private final int targetChunkChars;
    private final int overlapChars;
    private final int minChunkChars;
    private final int maxChunkChars;

    public SemanticTextSplitter() {
        // 中文技术文档经验值
        this(2000, 250, 500, 4000);
    }

    public SemanticTextSplitter(int targetChunkChars, int overlapChars, int minChunkChars, int maxChunkChars) {
        if (overlapChars >= targetChunkChars) {
            throw new IllegalArgumentException("overlapChars must be less than targetChunkChars");
        }
        this.targetChunkChars = targetChunkChars;
        this.overlapChars = overlapChars;
        this.minChunkChars = minChunkChars;
        this.maxChunkChars = maxChunkChars;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) return List.of();

        // 预处理：剥离 markdown 图片引用
        text = MARKDOWN_IMAGE.matcher(text).replaceAll("");

        List<String> sections = splitByHeadings(text);
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= targetChunkChars) {
                chunks.add(section);
            } else {
                chunks.addAll(splitByParagraph(section));
            }
        }

        chunks = mergeSmallChunks(chunks);
        return addOverlap(chunks);
    }

    private List<String> splitByHeadings(String text) {
        String[] lines = text.split("\n", -1);
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCodeBlock = false;

        for (String line : lines) {
            // 代码块内不能按标题切，否则把代码和上下文分家
            if (CODE_FENCE.matcher(line).find()) {
                inCodeBlock = !inCodeBlock;
            }
            boolean isHeading = !inCodeBlock && HEADING_LINE.matcher(line).matches();
            if (isHeading && current.length() > 0) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            String last = current.toString().trim();
            if (!last.isEmpty()) sections.add(last);
        }
        return sections;
    }

    /**
     * 段落级切分：把文本拆成"文本段"与"代码块"两种单元，贪心累积到 targetChunkChars。
     * 代码块被视为原子单位；如果单个代码块 > targetChunkChars，独立成 chunk（超过 max 才硬切）。
     */
    private List<String> splitByParagraph(String section) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (Unit u : parseUnits(section)) {
            if (u.isCode) {
                // 代码块当前如能放下就放；超 target 但 ≤ max 独立成片；超 max 才硬切
                if (current.length() + u.text.length() + 2 > targetChunkChars && current.length() > 0) {
                    flush(current, chunks);
                }
                if (u.text.length() <= maxChunkChars) {
                    if (current.length() > 0) current.append("\n\n");
                    current.append(u.text);
                } else {
                    flush(current, chunks);
                    for (int i = 0; i < u.text.length(); i += targetChunkChars) {
                        chunks.add(u.text.substring(i, Math.min(i + targetChunkChars, u.text.length())));
                    }
                }
                continue;
            }

            // 文本段
            if (u.text.length() > targetChunkChars) {
                flush(current, chunks);
                chunks.addAll(splitBySentence(u.text));
                continue;
            }
            if (current.length() + u.text.length() + 2 > targetChunkChars && current.length() > 0) {
                flush(current, chunks);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(u.text);
        }
        flush(current, chunks);
        return chunks;
    }

    /**
     * 把一个 section 拆成有序的 Unit 列表：代码块 / 文本段交替。
     * 用行级状态机扫一遍，围栏内的行都归到代码块 Unit，围栏外按空行分段。
     */
    private List<Unit> parseUnits(String section) {
        List<Unit> units = new ArrayList<>();
        String[] lines = section.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        boolean inCode = false;

        for (String line : lines) {
            boolean fence = CODE_FENCE.matcher(line).find();
            if (fence) {
                if (!inCode) {
                    // 进入代码块前：先收编累积的普通文本
                    flushTextUnit(buf, units);
                    inCode = true;
                    buf.append(line).append("\n");
                } else {
                    // 退出代码块：整块作为 code unit
                    buf.append(line);
                    units.add(new Unit(buf.toString(), true));
                    buf.setLength(0);
                    inCode = false;
                }
                continue;
            }
            buf.append(line).append("\n");
        }
        if (buf.length() > 0) {
            if (inCode) {
                // 围栏未闭合的兜底：当作代码 unit 处理
                units.add(new Unit(buf.toString().trim(), true));
            } else {
                flushTextUnit(buf, units);
            }
        }
        return units;
    }

    /**
     * 普通文本段落按空行再切成多个 text Unit。
     */
    private void flushTextUnit(StringBuilder buf, List<Unit> sink) {
        if (buf.length() == 0) return;
        String text = buf.toString();
        buf.setLength(0);
        for (String p : PARAGRAPH_BREAK.split(text)) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) sink.add(new Unit(trimmed, false));
        }
    }

    private List<String> splitBySentence(String paragraph) {
        String[] sentences = SENTENCE_END.split(paragraph);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String s : sentences) {
            if (s.isEmpty()) continue;
            if (s.length() > maxChunkChars) {
                flush(current, chunks);
                for (int i = 0; i < s.length(); i += targetChunkChars) {
                    int end = Math.min(i + targetChunkChars, s.length());
                    chunks.add(s.substring(i, end));
                }
                continue;
            }
            if (current.length() + s.length() > targetChunkChars && current.length() > 0) {
                flush(current, chunks);
            }
            current.append(s);
        }
        flush(current, chunks);
        return chunks;
    }

    private List<String> mergeSmallChunks(List<String> chunks) {
        if (chunks.isEmpty()) return chunks;
        List<String> merged = new ArrayList<>();
        StringBuilder pending = new StringBuilder(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String next = chunks.get(i);
            if (pending.length() < minChunkChars && pending.length() + next.length() + 2 <= maxChunkChars) {
                pending.append("\n\n").append(next);
            } else {
                merged.add(pending.toString());
                pending.setLength(0);
                pending.append(next);
            }
        }
        if (pending.length() > 0) merged.add(pending.toString());
        return merged;
    }

    /**
     * overlap 落到"最近一个句末标点"之后，避免把连贯句子切成两半。
     * 如果尾部 N 字符内找不到句末标点，退化为按字符硬取。
     */
    private List<String> addOverlap(List<String> chunks) {
        if (overlapChars <= 0 || chunks.size() < 2) return chunks;

        List<String> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String tail = smartTail(prev, overlapChars);
            result.add(tail + "\n\n" + chunks.get(i));
        }
        return result;
    }

    /**
     * 从末尾取约 overlapChars 的"尾巴"，但优先选择在句号之后切开。
     * 算法：从倒数第 overlapChars 字符起向后找第一个句末标点，找到就从该点之后开始取；找不到就原样取末尾 N 字符。
     */
    private String smartTail(String text, int wanted) {
        if (text.length() <= wanted) return text;
        int start = text.length() - wanted;
        // 向后小幅扫一点（最多 wanted/2）找句末标点，找到就从标点之后开始
        int scanLimit = Math.min(text.length() - 1, start + wanted / 2);
        for (int i = start; i < scanLimit; i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '\n') {
                return text.substring(i + 1).trim();
            }
        }
        return text.substring(start);
    }

    private static void flush(StringBuilder buf, List<String> sink) {
        if (buf.length() > 0) {
            String s = buf.toString().trim();
            if (!s.isEmpty()) sink.add(s);
            buf.setLength(0);
        }
    }

    /** 内部单元：文本段 or 代码块 */
    private static final class Unit {
        final String text;
        final boolean isCode;
        Unit(String text, boolean isCode) { this.text = text; this.isCode = isCode; }
    }

    public int getTargetChunkChars() { return targetChunkChars; }
    public int getOverlapChars() { return overlapChars; }
    public int getMinChunkChars() { return minChunkChars; }
    public int getMaxChunkChars() { return maxChunkChars; }
}
