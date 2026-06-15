package cn.bugstack.ai.domain.agent.service.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * G1 (F6) Human Approval Gate — A 阶段：高风险工具识别白名单。
 * <p>
 * <b>来源</b>：yml 配置 {@code agent.human-approval.required-tools}（列表）。
 * <p>
 * <b>匹配策略（MVP）</b>：精确匹配 toolName，不做关键词/后缀模糊匹配（Codex 第 42 轮建议第 5 条）。
 * 不同 MCP server 的工具名前缀不统一，提前模糊匹配会引入误判；等真实出现统一前缀再补规范化函数。
 * <p>
 * <b>保护机制</b>：白名单为空时 {@link #isHighRisk(String)} 永远返 false —— 即使
 * {@code agent.human-approval.enabled=true} 也完全 no-op，避免误把所有工具调用都拉去要审批。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "agent.human-approval")
public class HighRiskToolRegistry {

    /** yml 列表注入；用 LinkedHashSet 保留配置顺序便于日志排查，HashSet 也可。 */
    private final Set<String> requiredTools = new LinkedHashSet<>();

    public Set<String> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(Set<String> tools) {
        requiredTools.clear();
        if (tools != null) requiredTools.addAll(tools);
    }

    /**
     * 启动诊断：把绑定后的白名单打到 INFO，方便排查"配置到底加载了没/绑的是什么"。
     * 空白名单时显式提示这是 no-op 灰度态，避免误判为"开关没生效"。
     */
    @PostConstruct
    public void logLoadedWhitelist() {
        if (requiredTools.isEmpty()) {
            log.info("[HighRiskToolRegistry] human-approval required-tools 为空 → 审批门 no-op（任何工具都不会触发审批）");
        } else {
            log.info("[HighRiskToolRegistry] human-approval required-tools 已加载 {} 个，精确匹配（区分大小写）：{}",
                    requiredTools.size(), requiredTools);
        }
    }

    /**
     * 判断 toolName 是否命中高风险白名单。
     * <p>
     * 空名 / 空白名单 → 永远 false（保护机制，禁止"开关一开全部要审批"的误伤）。
     */
    public boolean isHighRisk(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        if (requiredTools.isEmpty()) return false;
        return requiredTools.contains(toolName);
    }

    /** 暴露不可变副本给观测/审计代码，避免外部直接修改 internal Set。 */
    public Set<String> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(requiredTools));
    }
}
