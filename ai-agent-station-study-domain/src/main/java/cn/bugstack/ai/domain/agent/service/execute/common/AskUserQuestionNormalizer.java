package cn.bugstack.ai.domain.agent.service.execute.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Normalizes the occasional non-conforming {@code ask_user.questions} value produced by an LLM.
 * The schema requires an array, but some models serialize that array into a JSON string. A second
 * failure mode is losing the nested escaping around quotes in natural-language question text.
 */
public final class AskUserQuestionNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AskUserQuestionNormalizer() {
    }

    /** Unwrap at most three encoded layers. Unparseable plain text remains a textual question. */
    public static JsonNode normalize(JsonNode questionsNode) {
        JsonNode current = questionsNode;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            if (current.isObject() && current.has("questions")) {
                current = current.get("questions");
                continue;
            }
            if (!current.isTextual()) break;
            String text = current.asText().trim();
            if (text.isEmpty()) break;
            JsonNode parsed = parseJsonOrRepairNestedQuotes(text);
            if (parsed == null || parsed.equals(current)) break;
            current = parsed;
        }
        return current;
    }

    private static JsonNode parseJsonOrRepairNestedQuotes(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception firstFailure) {
            if (!(text.startsWith("[") || text.startsWith("{"))) return null;
            String repaired = escapeUnescapedQuotesInsideJsonStrings(text);
            if (repaired.equals(text)) return null;
            try {
                return MAPPER.readTree(repaired);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * Repairs quotes such as {@code "question":"比如"下周五""}. Inside a JSON string a quote is
     * treated as structural only when the next non-whitespace character can legally end a key/value.
     * This is intentionally narrow and is used only after strict JSON parsing has failed.
     */
    private static String escapeUnescapedQuotesInsideJsonStrings(String json) {
        StringBuilder repaired = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (!inString) {
                repaired.append(current);
                if (current == '"') inString = true;
                continue;
            }
            if (escaped) {
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
                continue;
            }
            if (current != '"') {
                repaired.append(current);
                continue;
            }

            int next = i + 1;
            while (next < json.length() && Character.isWhitespace(json.charAt(next))) next++;
            boolean structural = next >= json.length()
                    || json.charAt(next) == ':'
                    || json.charAt(next) == ','
                    || json.charAt(next) == '}'
                    || json.charAt(next) == ']';
            if (structural) {
                repaired.append(current);
                inString = false;
            } else {
                repaired.append('\\').append(current);
            }
        }
        return repaired.toString();
    }
}
