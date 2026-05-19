package cn.bugstack.ai.domain.agent.service.security;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2.5 14.3 输出审核：对 LLM 返回文本做轻量内容安全检查。
 * <p>
 * 检查项：SQL 注入片段、明文密钥/Token、XSS 脚本、成人/暴力关键词匹配。
 * 命中返回警告信息而非原文（不阻断请求，避免重试成本）。
 * 重度审核建议接 Llama Guard / OpenAI Moderation API（P3）。
 */
@Slf4j
public class OutputModerationFilter {

    // SQL 注入特征
    private static final Pattern SQL_INJECTION = Pattern.compile(
            "(?i)(\\bDROP\\s+TABLE\\b|\\bDELETE\\s+FROM\\b|\\bUNION\\s+SELECT\\b|\\b--\\s|\\b1\\s*=\\s*1\\b|\\bOR\\s+'1'\\s*=\\s*'1)");
    // 明文密钥/Token 泄漏（sk- / AIza / eyJ 模式）
    private static final Pattern SECRET_LEAK = Pattern.compile(
            "(sk-[a-zA-Z0-9_-]{15,}|AIza[a-zA-Z0-9_\\-]{30,}|eyJ[a-zA-Z0-9_\\-]{20,}\\.[a-zA-Z0-9_\\-]{20,}\\.[a-zA-Z0-9_\\-]{10,})");
    // XSS
    private static final Pattern XSS = Pattern.compile(
            "(?i)<\\s*script[^>]*>|javascript\\s*:|on\\w+\\s*=\\s*\"");
    // 成人/暴力关键词
    private static final Pattern NSFW = Pattern.compile(
            "(?i)\\b(porn|xxx|nsfw|child\\s*abuse|violence\\s*against)\\b");

    private OutputModerationFilter() {}

    /**
     * 检查输出内容。null/blank 直接通过。
     * @return 如果内容安全返回原文本，否则返回警告消息
     */
    public static String check(String output) {
        if (output == null || output.isBlank()) return output;

        String result = output;
        int matchedLen = 0;

        Object[][] patterns = {
                {SQL_INJECTION, "sql_injection"},
                {SECRET_LEAK, "secret_leak"},
                {XSS, "xss"},
                {NSFW, "nsfw"}
        };

        for (Object[] entry : patterns) {
            Pattern pattern = (Pattern) entry[0];
            String category = (String) entry[1];
            Matcher m = pattern.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                matchedLen += m.group().length();
                m.appendReplacement(sb, "[REDACTED: " + category + "]");
            }
            m.appendTail(sb);
            result = sb.toString();
        }

        if (matchedLen > 0) {
            log.warn("[OutputModeration] redacted_len={} output_len={}", matchedLen, output.length());
            if (matchedLen > output.length() * 0.5) {
                return "[Content moderated: multiple sensitive patterns detected. Please rephrase your request without sensitive content.]";
            }
            return result;
        }
        return output;
    }
}
