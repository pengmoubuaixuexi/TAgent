package cn.bugstack.ai.domain.agent.service.security;

import java.util.regex.Pattern;

/**
 * 输出过滤：清理 LLM 返回中的非内容标签（如 <think> 思考块）。
 * 在 SSE 输出给前端之前调用。
 */
public class OutputFilter {

    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern CLOSED_TOOL_CALL_BLOCK = Pattern.compile("(?s)<tool_call>.*?</tool_call>");
    private static final Pattern TOOL_XML_LINE = Pattern.compile("(?m)^\\s*</?(tool_call|function=[^>]+|parameter=[^>]+)>\\s*$");

    /**
     * 去掉 <think>...</think> 思考块。null/blank 穿通。
     */
    public static String stripThinkTags(String text) {
        if (text == null || text.isBlank()) return text;
        return THINK_BLOCK.matcher(text).replaceAll("").trim();
    }

    public static String cleanForUser(String text) {
        if (text == null || text.isBlank()) return text;
        return stripToolCallArtifacts(stripThinkTags(text));
    }

    public static String stripToolCallArtifacts(String text) {
        if (text == null || text.isBlank()) return text;

        String cleaned = text;
        String marker = "<parameter=content>";
        int contentStart = cleaned.indexOf(marker);
        if (contentStart >= 0) {
            cleaned = cleaned.substring(contentStart + marker.length());
            cleaned = cleaned.replaceAll("(?s)</parameter>\\s*</function>\\s*</tool_call>\\s*$", "");
            cleaned = cleaned.replaceAll("(?s)</parameter>\\s*$", "");
            return cleaned.trim();
        }

        cleaned = CLOSED_TOOL_CALL_BLOCK.matcher(cleaned).replaceAll("");
        cleaned = TOOL_XML_LINE.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    private OutputFilter() {}
}
