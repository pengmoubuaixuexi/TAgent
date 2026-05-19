package cn.bugstack.ai.domain.agent.service.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 启发式 RAG 路由（P0.1.4）。
 * <p>
 * 规则原则：<b>偏保守</b>——能不跳过就不跳过。误跳过 = 用户拿不到知识库内容（损失大），
 * 误检索 = 多花一次 RRF + rerank 调用（损失小）。所以默认 retrieve=true。
 * <p>
 * 跳过条件（任一命中即 skip）：
 * <ol>
 *   <li>长度 &lt; minLengthChars：太短不可能是有意义的提问</li>
 *   <li>纯净的招呼词：白名单短语完全匹配（"你好"、"谢谢"、"再见"、"hi" 等）</li>
 *   <li>纯非汉字非字母（标点 / 表情 / "..."）</li>
 * </ol>
 * <p>
 * <b>不</b>跳过的（避免误伤）：
 * <ul>
 *   <li>含问号（中英文）— 大概率是问题</li>
 *   <li>含技术关键词（项目、文档、模型、配置、错误...）</li>
 *   <li>长度 ≥ minLengthChars 的任意自然语言</li>
 * </ul>
 * <p>
 * 当 {@code agent.rag-router.llm-enabled=true} 时，{@link LlmRagRouter} 替代本类。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.rag-router.llm-enabled", havingValue = "false", matchIfMissing = true)
public class HeuristicRagRouter implements IRagRouter {

    /** 完全匹配（trim + lowercase 后）即视为问候/闲聊 */
    private static final Set<String> GREETINGS = Set.of(
            "你好", "您好", "hi", "hello", "hey", "嗨",
            "谢谢", "感谢", "thanks", "thank you", "thx",
            "再见", "拜拜", "bye", "goodbye",
            "好的", "ok", "okay", "嗯", "嗯嗯", "哦", "哈哈", "呵呵",
            "早上好", "晚上好", "晚安", "中午好",
            "?", "？"
    );

    @Value("${agent.rag-router.min-length-chars:4}")
    private int minLengthChars;

    @Value("${agent.rag-router.enabled:true}")
    private boolean enabled;

    @Override
    public boolean shouldRetrieve(String query) {
        if (!enabled) return true;
        if (query == null) return true;

        String trimmed = query.trim();
        if (trimmed.isEmpty()) return false;

        // 1. 太短：留点容差，比如 "你是谁?" 6 字符也跳过会误伤；阈值 ≤ 4 比较稳
        if (trimmed.length() < minLengthChars) {
            log.debug("rag-router skip: too short, len={} query={}", trimmed.length(), trimmed);
            return false;
        }

        // 2. 纯标点 / 表情：没什么可检索的
        boolean hasMeaningfulChar = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || isCJK(c)) {
                hasMeaningfulChar = true;
                break;
            }
        }
        if (!hasMeaningfulChar) {
            log.debug("rag-router skip: no meaningful char, query={}", trimmed);
            return false;
        }

        // 3. 纯净的招呼词：白名单完全匹配
        String key = trimmed.toLowerCase();
        if (GREETINGS.contains(key)) {
            log.debug("rag-router skip: pure greeting, query={}", trimmed);
            return false;
        }

        return true;
    }

    private static boolean isCJK(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
