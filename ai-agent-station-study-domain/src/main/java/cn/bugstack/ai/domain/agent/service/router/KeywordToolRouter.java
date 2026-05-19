package cn.bugstack.ai.domain.agent.service.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 基于关键词的轻量工具路由（P0.1.5 MVP）。
 * <p>
 * 思路：把 query 切成 tokens，对每个工具的 mcpName + description 算 token 命中数，加权打分。
 * 不依赖 LLM、不依赖 embedding，零成本启动；等积累指标说明误判较多时再换 embedding 版本。
 * <p>
 * 计分规则：
 * <ul>
 *   <li>命中 mcpName：+3 / token（工具名往往就是关键词，比如"csdn"、"grafana"）</li>
 *   <li>命中 description：+1 / token</li>
 *   <li>命中"动作动词"（发布/查询/查/获取/读取）：+2（这类词常对应工具调用意图）</li>
 * </ul>
 */
@Slf4j
@Service
public class KeywordToolRouter implements IToolRouter {

    /** 把 CJK 字符切成单字 + 西文按空白/标点切；MVP 阶段够用 */
    private static final Pattern WS_OR_PUNCT = Pattern.compile("[\\s,，.。;；:：!！?？()（）\\[\\]【】\"'`]+");

    /** 与"调用工具"语义强相关的动词；命中加分 */
    private static final List<String> ACTION_VERBS = List.of(
            "发布", "发", "推送", "查询", "查看", "查", "获取", "读取", "拉取",
            "publish", "query", "fetch", "get", "read"
    );

    @Override
    public List<Decision> route(String query, List<Candidate> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (query == null || query.isBlank()) {
            // 没 query 信号时全部 keep（保守）
            return candidates.stream()
                    .map(c -> new Decision(c.mcpId(), 0.0, true))
                    .toList();
        }

        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(normalizedQuery);

        List<Decision> ranked = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            double score = score(c, tokens, normalizedQuery);
            ranked.add(new Decision(c.mcpId(), score, false));
        }
        ranked.sort(Comparator.comparingDouble(Decision::score).reversed());

        // 截 top-K，剩下的标记 keep=false
        int keepN = Math.min(topK, ranked.size());
        List<Decision> result = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            Decision d = ranked.get(i);
            result.add(new Decision(d.mcpId(), d.score(), i < keepN));
        }
        return result;
    }

    private double score(Candidate c, List<String> queryTokens, String fullQuery) {
        String name = c.mcpName() == null ? "" : c.mcpName().toLowerCase(Locale.ROOT);
        String desc = c.description() == null ? "" : c.description().toLowerCase(Locale.ROOT);

        double s = 0.0;
        for (String tok : queryTokens) {
            if (tok.length() < 2) continue;     // 单字噪声大，跳过（CJK 单字会再走下面的 contains）
            if (name.contains(tok)) s += 3.0;
            if (desc.contains(tok)) s += 1.0;
        }
        // 整串包含 mcpName（"我想发到 csdn"）走快通道
        if (!name.isBlank() && fullQuery.contains(name)) s += 5.0;

        for (String verb : ACTION_VERBS) {
            if (fullQuery.contains(verb)) {
                // 动作动词只在工具有名字命中时再加分，避免给所有工具白送分
                if (s > 0) s += 2.0;
                break;
            }
        }
        return s;
    }

    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        for (String chunk : WS_OR_PUNCT.split(s)) {
            if (chunk.isBlank()) continue;
            tokens.add(chunk);
            // 中文额外切单字 / 双字组合，提高召回
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    if (i + 1 < chunk.length()) tokens.add(chunk.substring(i, i + 2));
                    tokens.add(String.valueOf(c));
                }
            }
        }
        return tokens;
    }
}
