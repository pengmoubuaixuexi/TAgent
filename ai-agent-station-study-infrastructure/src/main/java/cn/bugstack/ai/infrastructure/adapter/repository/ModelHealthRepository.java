package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IModelHealthRepository;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelFailure;
import cn.bugstack.ai.domain.agent.service.model.failover.ModelTarget;
import cn.bugstack.ai.domain.agent.service.model.health.ModelHealthRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/** V071 {@code ai_model_health}. */
@Repository
public class ModelHealthRepository implements IModelHealthRepository {
    private final JdbcTemplate jdbc;

    public ModelHealthRepository(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public List<ModelHealthRecord> loadAll() {
        return jdbc.query("SELECT * FROM ai_model_health", (rs, row) -> new ModelHealthRecord(
                new ModelTarget(rs.getString("api_id"), rs.getString("model_name")),
                "HEALTHY".equals(rs.getString("state")), rs.getInt("consecutive_failures"),
                kind(rs.getString("last_failure_kind")), (Integer) rs.getObject("last_failure_status"), rs.getString("last_failure_message"),
                millis(rs, "last_failure_at"), millis(rs, "last_success_at"), millis(rs, "unhealthy_since"),
                millis(rs, "last_probe_at"), millis(rs, "last_notified_at")));
    }

    @Override public void save(ModelHealthRecord r) {
        String message = r.lastFailureMessage() == null || r.lastFailureMessage().length() <= 255
                ? r.lastFailureMessage() : r.lastFailureMessage().substring(0, 254) + "…";
        jdbc.update("""
                INSERT INTO ai_model_health(api_id,model_name,state,consecutive_failures,last_failure_kind,last_failure_status,
                  last_failure_message,last_failure_at,last_success_at,unhealthy_since,last_probe_at,last_notified_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE state=VALUES(state),consecutive_failures=VALUES(consecutive_failures),
                  last_failure_kind=VALUES(last_failure_kind),last_failure_status=VALUES(last_failure_status),
                  last_failure_message=VALUES(last_failure_message),last_failure_at=VALUES(last_failure_at),
                  last_success_at=VALUES(last_success_at),unhealthy_since=VALUES(unhealthy_since),
                  last_probe_at=VALUES(last_probe_at),last_notified_at=VALUES(last_notified_at)
                """, r.target().apiId(), r.target().model(), r.healthy() ? "HEALTHY" : "UNHEALTHY", r.consecutiveFailures(),
                r.lastFailureKind() == null ? null : r.lastFailureKind().name(), r.lastFailureStatus(), message,
                time(r.lastFailureAt()), time(r.lastSuccessAt()), time(r.unhealthySince()), time(r.lastProbeAt()), time(r.lastNotifiedAt()));
    }

    @Override public void delete(ModelTarget target) {
        jdbc.update("DELETE FROM ai_model_health WHERE api_id=? AND model_name=?", target.apiId(), target.model());
    }

    private static ModelFailure.Kind kind(String value) {
        if (value == null) return null;
        try {
            return ModelFailure.Kind.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return ModelFailure.Kind.TRANSIENT;
        }
    }

    private static Long millis(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.getTime();
    }

    private static Timestamp time(Long millis) { return millis == null ? null : new Timestamp(millis); }
}
