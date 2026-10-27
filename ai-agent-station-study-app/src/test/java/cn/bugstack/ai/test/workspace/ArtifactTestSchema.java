package cn.bugstack.ai.test.workspace;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/** H2 (MySQL mode) version of the production artifact schema: V069 plus the V072 provenance columns. */
final class ArtifactTestSchema {

    private ArtifactTestSchema() {
    }

    static void apply(JdbcTemplate db) throws Exception {
        Path migration = Path.of("../docs/dev-ops/sql-migrations/V069__artifacts_feedback.sql");
        if (!Files.exists(migration)) migration = Path.of("docs/dev-ops/sql-migrations/V069__artifacts_feedback.sql");
        for (String sql : Files.readString(migration).replace("JSON NOT NULL", "CLOB NOT NULL")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "").split(";")) {
            if (!sql.isBlank()) db.execute(sql);
        }
        for (String column : new String[]{"source_kind VARCHAR(16)", "source_run_id VARCHAR(128)", "base_revision INT",
                "source_attachment_id VARCHAR(64)", "source_call_hash CHAR(64)"}) {
            db.execute("ALTER TABLE ai_run_artifact_revision ADD COLUMN " + column);
        }
        // Only the columns the artifact service reads to check that an artifact was attached in this session.
        db.execute("CREATE TABLE ai_chat_file(attachment_id VARCHAR(64) PRIMARY KEY,owner_user_id VARCHAR(64),session_id VARCHAR(255),"
                + "kind VARCHAR(16),status VARCHAR(16),artifact_id VARCHAR(64),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        // The full artifact list shows the question of each artifact's run.
        db.execute("CREATE TABLE ai_run_history(run_id VARCHAR(128) PRIMARY KEY,question CLOB)");
    }
}
