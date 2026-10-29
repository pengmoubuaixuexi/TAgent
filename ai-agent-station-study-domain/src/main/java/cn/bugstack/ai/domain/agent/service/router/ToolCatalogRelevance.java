package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative lexical evidence for catalog retrieval, not a replacement for semantic ranking. */
public final class ToolCatalogRelevance {
    private ToolCatalogRelevance() {}

    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9]*|[\\p{IsHan}]+");
    // A generic web-search service need not mention every topic (DDD, Java, etc.).
    // Corroborate the compound operation/object, never a tool/provider name or "search" alone.
    private static final String WEB = "(?:网页|网络|全网|互联网|联网|网上)";
    private static final String SEARCH = "(?:搜索|检索|搜寻|查找)";
    private static final Pattern WEB_SEARCH = Pattern.compile(WEB + "\\s*" + SEARCH + "|" + SEARCH
            + "\\s*(?:实时|在线|公开|通用)?\\s*" + WEB
            + "|\\b(?:web|internet|online)\\s+search\\b"
            + "|\\b(?:search|find)\\s+(?:(?:the|public|online)\\s+){0,2}(?:web|internet)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED = Pattern.compile(
            "(?:不支持|不能|无法|不提供|\\b(?:not|no|without|cannot)\\b|\\bcan't\\b)[^，,。.;；\\n]{0,16}$",
            Pattern.CASE_INSENSITIVE);
    // Query/catalog scaffolding is not evidence that a tool supports the requested capability.
    // No service names or domain-specific exclusions: private and public catalogs use the same rules.
    private static final Set<String> BOILERPLATE = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "what", "how", "about", "can", "you",
            "tool", "tools", "use", "using", "get", "find", "search", "query", "return", "returns", "data",
            "information", "content", "result", "results", "please",
            "工具", "用户", "查询", "搜索", "查找", "获取", "返回", "结果", "信息", "数据", "内容", "资料",
            "支持", "根据", "用于", "提供", "可以", "进行", "帮助", "使用", "相关", "一个", "指定", "当前",
            "关于", "通过", "需要", "能够", "并且", "以及", "包括", "例如", "这些", "那些", "下载", "链接");

    /** Recognize explicit names inside a sentence/list, but never inside a different identifier. */
    public static int explicitNamePosition(String need, String name) {
        if (need == null || name == null || name.isBlank()) return -1;
        var matcher = Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(name)
                + "(?![a-z0-9_-])", Pattern.CASE_INSENSITIVE).matcher(need);
        return matcher.find() ? matcher.start() : -1;
    }

    /**
     * A short capability description corroborates a moderate vector hit. Expanded sample intents
     * are deliberately not evidence: "papers about learning" does not make a paper tool a tutorial
     * search engine. A single generic overlap (data/download/search) is also insufficient.
     */
    public static int score(String need, AiMcpToolCatalogVO tool) {
        if (need == null || tool == null) return 0;
        if (explicitNamePosition(need, tool.getToolName()) >= 0) return 1000;
        Set<String> query = terms(need);
        if (query.isEmpty()) return 0;
        String description = tool.getToolDescriptionZh();
        if (description == null || description.isBlank()) description = tool.getToolDescription();
        // Untranslated private tools may have long parameter documentation; do not match a
        // coincidental parameter example somewhere in that documentation.
        description = description == null ? "" : description.split("(?i)\\n\\s*(?:args|parameters|参数)\\s*[:：]", 2)[0];
        if (description.length() > 500) description = description.substring(0, 500);
        int requestedWebSearch = webSearchEvidence(need);
        if (requestedWebSearch > 0 && webSearchEvidence(description) < 0) return 0;
        if (requestedWebSearch > 0 && webSearchEvidence(safe(tool.getToolName()) + " " + description) > 0) return 100;
        Set<String> capability = terms(safe(tool.getToolName()) + " " + description);
        long matched = query.stream().filter(capability::contains).count();
        double coverage = (double) matched / query.size();
        if (matched < 2 && coverage < .5 || coverage < .12) return 0;
        return (int) Math.round(coverage * 100) + (int) Math.min(matched, 10) * 5;
    }

    /** Literal bilingual corroboration only; fetching a URL or searching papers is not web search. */
    private static int webSearchEvidence(String text) {
        String normalized = safe(text).replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[_:/-]+", " ");
        var matcher = WEB_SEARCH.matcher(normalized);
        boolean positive = false;
        while (matcher.find()) {
            String prefix = normalized.substring(Math.max(0, matcher.start() - 32), matcher.start());
            if (NEGATED.matcher(prefix).find()) return -1;
            positive = true;
        }
        return positive ? 1 : 0;
    }

    private static Set<String> terms(String text) {
        String normalized = safe(text).replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        // Split before Han bigrams so removing "用户" does not leave the artificial "户提"
        // in "用户提问" as a second independent match for a web search tool.
        for (String boilerplate : BOILERPLATE) {
            if (Character.UnicodeScript.of(boilerplate.codePointAt(0)) == Character.UnicodeScript.HAN)
                normalized = normalized.replace(boilerplate, " ");
        }
        Set<String> result = new LinkedHashSet<>();
        var matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = word.codePoints().toArray();
                for (int i = 0; i + 1 < points.length; i++) result.add(new String(points, i, 2));
            } else if (word.length() >= 2) result.add(word);
        }
        result.removeAll(BOILERPLATE);
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
