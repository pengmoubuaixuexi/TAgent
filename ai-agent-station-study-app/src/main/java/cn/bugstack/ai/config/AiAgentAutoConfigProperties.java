package cn.bugstack.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * AI Agent 自动装配配置属性
 *
 * @author TAgent
 * 2025/1/15 10:00
 */
@Data
@ConfigurationProperties(prefix = "spring.ai.agent.auto-config")
public class AiAgentAutoConfigProperties {

    /**
     * 是否启用AI Agent Client自动装配
     */
    private boolean enabled = false;

    /**
     * 启动时是否预装配所有 enabled agent（懒加载模式下设为 false）
     */
    private boolean preloadEnabled = false;

}