package cn.bugstack.ai.domain.agent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2.5 14.6 敏感工具白名单：配置化标记哪些工具需要 human-in-the-loop 审批。
 * <p>
 * 匹配策略：工具全名精确匹配。未匹配的工具默认放行。
 * 配置项 {@code agent.sensitive-tools.names}，逗号分隔。
 */
@Slf4j
@Component
public class SensitiveToolRegistry {

    private final Set<String> sensitiveNames;
    private final boolean enabled;

    public SensitiveToolRegistry(
            @Value("${agent.sensitive-tools.names:}") String names,
            @Value("${agent.sensitive-tools.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (names != null && !names.isBlank() && enabled) {
            Set<String> s = ConcurrentHashMap.newKeySet();
            for (String n : names.split(",")) {
                String trimmed = n.trim();
                if (!trimmed.isEmpty()) s.add(trimmed);
            }
            this.sensitiveNames = s;
            log.info("[SensitiveTool] enabled=true, names={}", s);
        } else {
            this.sensitiveNames = Collections.emptySet();
            log.info("[SensitiveTool] enabled={}, no names configured", enabled);
        }
    }

    public boolean isSensitive(String toolName) {
        return enabled && toolName != null && sensitiveNames.contains(toolName);
    }

    public Set<String> getSensitiveNames() {
        return Collections.unmodifiableSet(sensitiveNames);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
