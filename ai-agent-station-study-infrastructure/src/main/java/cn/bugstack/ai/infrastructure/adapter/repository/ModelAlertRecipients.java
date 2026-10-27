package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelAlertRecipients;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ModelAlertRecipients implements IModelAlertRecipients {
    private final JdbcTemplate jdbc;

    public ModelAlertRecipients(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public List<String> administrators() {
        return jdbc.queryForList("SELECT user_id FROM admin_user WHERE role='ADMIN' AND status=1 ORDER BY id", String.class);
    }
}
