package cn.bugstack.ai.domain.agent.service.memory.longterm;

import java.util.regex.Pattern;

/** Conservative source check for automatically inferred skill memories, not a general entailment engine. */
public final class SkillMemoryEvidence {
    private SkillMemoryEvidence() {}

    private static final Pattern SELF_CLAIM = Pattern.compile(
            "(?:我|本人)(?:目前|现在|已经|一直|比较|主要|也|还|很|已|对)?(?:熟悉|掌握|精通|擅长|会用|会|使用|在用)"
                    + "|我的(?:技能|技术栈|专长)(?:是|包括|包含|有|[:：])"
                    + "|\\bI\\s+(?:(?:already|currently|also)\\s+)?(?:know|use|can\\s+use|am\\s+(?:familiar\\s+with|proficient\\s+in|skilled\\s+in))\\b"
                    + "|\\bmy\\s+(?:skills|tech\\s+stack)\\s+(?:include|includes|is|are)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_CLAIM = Pattern.compile(
            "[?？]|不会|不熟悉|不掌握|不擅长|不了解|没掌握|未掌握|尚未|还没|想学|想掌握|希望|打算|计划|假设|比如|例如|请|是否|能否"
                    + "|\\b(?:not|never|want|wish|plan|suppose|example)\\b|don['’]t|can['’]t",
            Pattern.CASE_INSENSITIVE);

    public static boolean isSkillTopic(String topic) {
        return topic != null && (topic.startsWith("技能:") || topic.startsWith("技能：")
                || topic.regionMatches(true, 0, "skill:", 0, 6));
    }

    /** Store the exact assertion, not the extractor's stronger paraphrase. Missing/ambiguous evidence is skipped. */
    public static String verifiedContent(String userText, String evidence) {
        if (userText == null || evidence == null) return null;
        String quote = evidence.trim();
        if (quote.length() < 4 || quote.length() > 300 || !userText.contains(quote)) return null;
        if (NON_CLAIM.matcher(quote).find() || !SELF_CLAIM.matcher(quote).find()) return null;
        return quote;
    }
}
