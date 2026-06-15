package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * T11 (F5) Reflexion critique 结构化：解析器底座（A 阶段）。
 * <p>
 * <b>契约</b>：{@link #parse(String)} <b>绝不抛异常到主流程</b>。任何异常都被捕获后走 fallback，
 * 返回 {@link CritiqueRecord#fallback(String)}，保留原文供 Step2 兜底消费。
 * <p>
 * <b>解析顺序</b>（按 Codex 第 29 轮要求容忍脏输出）：
 * <ol>
 *   <li>剥 markdown code fence（{@code ```json ... ```} 或 {@code ``` ... ```}）。</li>
 *   <li>从首个 '{' 开始做括号配对截取 JSON 主体，丢弃前后说明文字。</li>
 *   <li>{@link ObjectMapper#readTree(String)} 解析；缺字段时取 null 不报错。</li>
 *   <li>未知 type 值通过 {@link CritiqueType#fromString(String)} 落到 {@link CritiqueType#OTHER}。</li>
 * </ol>
 * <p>
 * <b>不依赖 Spring 上下文</b>：纯工具类，单测可直接 new。
 */
@Slf4j
public final class CritiqueParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CritiqueParser() {
    }

    /**
     * 解析 Step3 模型输出的 critique 文本为结构化 {@link CritiqueRecord}。
     *
     * @param raw 模型原始输出，可能是干净 JSON / 带 code fence / 带前后说明 / 非 JSON 文字
     * @return 永不为 null；解析失败时 type=OTHER 且 rawText 保留原文
     */
    public static CritiqueRecord parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CritiqueRecord.fallback(raw);
        }
        try {
            String body = extractJsonBody(stripCodeFence(raw));
            if (body == null) {
                return CritiqueRecord.fallback(raw);
            }
            JsonNode node = MAPPER.readTree(body);
            if (node == null || !node.isObject()) {
                return CritiqueRecord.fallback(raw);
            }
            CritiqueType type = CritiqueType.fromString(textOrNull(node, "type"));
            String evidence = textOrNull(node, "evidence");
            String suggestion = textOrNull(node, "suggestion");
            return CritiqueRecord.builder()
                    .type(type)
                    .evidence(evidence)
                    .suggestion(suggestion)
                    .rawText(null)
                    .build();
        } catch (Exception e) {
            log.debug("CritiqueParser fallback, raw len={}, err={}", raw.length(), e.toString());
            return CritiqueRecord.fallback(raw);
        }
    }

    /**
     * 剥掉 markdown code fence。支持 ```json / ```JSON / ``` 三种打开符。
     * 没有围栏则原样返回。
     */
    static String stripCodeFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) return t;
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) return t;
        String afterOpen = t.substring(firstNl + 1);
        int closeIdx = afterOpen.lastIndexOf("```");
        if (closeIdx < 0) return afterOpen.trim();
        return afterOpen.substring(0, closeIdx).trim();
    }

    /**
     * 从字符串中截取 JSON 对象主体：从首个 '{' 开始做字符串感知的括号配对。
     * 处理"前面有说明文字、后面有总结"的脏输出。找不到返回 null。
     */
    static String extractJsonBody(String s) {
        int open = s.indexOf('{');
        if (open < 0) return null;

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isEmpty()) ? null : s;
    }
}
