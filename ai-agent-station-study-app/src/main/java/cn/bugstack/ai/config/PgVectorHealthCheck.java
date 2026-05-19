package cn.bugstack.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class PgVectorHealthCheck {

    @Resource
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void checkPgVectorAvailability() {
        try {
            pgVectorJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("PgVector health check: available");
        } catch (Exception e) {
            log.warn("PgVector health check: unavailable ({})", e.getMessage());
        }
    }
}
