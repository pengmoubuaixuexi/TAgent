package cn.bugstack.ai.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import cn.bugstack.ai.domain.agent.service.security.PiiMasker;

/**
 * P2.5 14.2 PII 脱敏：logback 转换器，对日志消息做 PII 掩码。
 * <p>
 * 在 logback-spring.xml 中配置为 %pii(%msg) 使用。
 */
public class PiiMaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String formatted = event.getFormattedMessage();
        if (formatted == null) return null;
        return PiiMasker.mask(formatted);
    }
}
