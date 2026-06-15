package cn.bugstack.ai.domain.agent.service.memory.conflict;

import cn.bugstack.ai.domain.agent.service.memory.MemoryTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * LLM 驱动的记忆冲突解决器（P2.1 10.3）。
 * <p>
 * 用 router-small 判断新旧记忆冲突是否需要覆盖/合并/跳过。主题分类法统一走 {@link MemoryTopics}。
 * <p>
 * 注意：单值主题（画像/计划/情况）的覆盖已在 {@code LongTermMemoryService.save()} 内做确定性处理、
 * 不再依赖本解析器；本类主要服务累加主题（技能/偏好）的 KEEP_BOTH/MERGE 判定。
 * LLM 失败时按主题类型 fallback（单值→OVERWRITE、累加→KEEP_BOTH，不丢数据）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "agent.memory.conflict", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmMemoryConflictResolver implements IMemoryConflictResolver {

    private static final String CONFLICT_PROMPT = """
            你是用户长期记忆的冲突判定器。给定主题(topic)、一条新记忆、若干条同主题旧记忆，判断如何处理新信息。

            重要：大多数记忆是「累加」而非「矛盾」。不同技能（技能:Java 和 技能:Python）都成立——
            用户同时拥有多项技能。只有当新信息使旧信息「明确不再为真」时才 OVERWRITE。

            主题分两类，规则不同：
            - 累加类：技能:* / 偏好:*（同前缀不同主体可共存）。默认 KEEP_BOTH。
              例：技能:Java + 技能:Python → KEEP_BOTH（两项技能都会）。
              例：技能:Spring "了解Spring Boot" + "专精Spring Security" → MERGE（同主题补充）。
            - 单值类：画像:*（姓名/职业/城市/收入/体重等封闭槽位）/ 计划:* / 情况:*
              （同一主题同时只有一个值成立）。新值与旧值不同 → OVERWRITE。
              例：画像:职业 "是Python工程师" → "是Java工程师" → OVERWRITE（职业变了）。
              例：画像:常驻城市 "在北京" → "搬到上海" → OVERWRITE（搬家了）。
              例：画像:税前月收入 "15000元" → "18000元" → OVERWRITE（涨薪）。
              但：若新值只是旧值的【更泛化、信息更少】的复述（不是真的变了），回 SKIP，保留信息更全的旧值：
              例：画像:职业 "Java后端开发工程师" → "程序员" → SKIP（"程序员"更泛，旧值更具体，别降级）。
              例：画像:职业 "Java后端开发工程师" → "用户是一名Java开发" → SKIP（同一职业、信息更少）。
            - 仅当新信息与旧信息逐字相同时才 SKIP。

            主题: %s
            新记忆: %s
            旧记忆:
            %s

            只回复一个词：OVERWRITE、MERGE、KEEP_BOTH 或 SKIP。""";
    // router-small 由 RouterPoolConfig 在 armory 阶段动态注册（bean 名 ai_client_router-small），
    // 时序上可能晚于本 @Service 构造，故首次使用时从 ApplicationContext 懒取（对齐 LongTermMemoryAdvisor）。
    // 历史 bug：曾用 @Qualifier("aiClient_router-small")（camelCase 拼错）永远注入不到 → chatClient 恒 null → 永远 fallback。
    @Autowired
    private ApplicationContext applicationContext;
    private volatile ChatClient chatClient;

    private ChatClient chatClient() {
        ChatClient local = chatClient;
        if (local == null && applicationContext != null) {
            try {
                local = applicationContext.getBean("ai_client_router-small", ChatClient.class);
                chatClient = local;
            } catch (Exception e) {
                log.debug("memory.conflict: router-small ChatClient 尚未就绪: {}", e.getMessage());
            }
        }
        return local;
    }

    @Override
    public Decision resolve(String topic, String newContent, List<String> existingContents) {
        if (existingContents == null || existingContents.isEmpty()) {
            return Decision.KEEP_BOTH;
        }
        ChatClient client = chatClient();
        if (client == null) {
            Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
            log.debug("memory.conflict: no router-small ChatClient, default {}", fallback);
            return fallback;
        }

        StringBuilder existingBlock = new StringBuilder();
        for (int i = 0; i < Math.min(existingContents.size(), 3); i++) {
            String s = existingContents.get(i);
            if (s != null && !s.isBlank()) {
                existingBlock.append("- ").append(s.length() > 200 ? s.substring(0, 200) : s).append("\n");
            }
        }
        if (existingBlock.isEmpty()) return Decision.KEEP_BOTH;

        String prompt = String.format(CONFLICT_PROMPT,
                topic != null ? topic : "general",
                newContent.length() > 200 ? newContent.substring(0, 200) : newContent,
                existingBlock.toString());

        String raw;
        try {
            raw = client.prompt(new Prompt(prompt)).call().content();
        } catch (Exception e) {
            Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
            log.debug("memory.conflict LLM call failed, fallback {}: {}", fallback, e.getMessage());
            return fallback;
        }

        if (raw == null || raw.isBlank()) {
            Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
            return fallback;
        }

        String upper = raw.trim().toUpperCase();
        for (Decision d : Decision.values()) {
            if (upper.startsWith(d.name())) return d;
        }
        Decision fallback = isSingularTopic(topic) ? Decision.OVERWRITE : Decision.KEEP_BOTH;
        log.debug("memory.conflict unknown response '{}', fallback {}", upper, fallback);
        return fallback;
    }

    /** 唯一性主题：同一主题同时只有一个值成立，变更时覆盖而非保留两份。
     *  统一委托 {@link MemoryTopics}（中文 V041 词表），杜绝与 LongTermMemoryService 的分类法漂移。 */
    static boolean isSingularTopic(String topic) {
        return MemoryTopics.isSingular(topic);
    }
}
