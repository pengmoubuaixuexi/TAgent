package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every externally addressed read/update includes the authenticated owner. Leases fence worker updates. */
@Repository
public class RagIngestionRepository {
    /** Daily knowledge quotas reset at midnight for the site's users, while timestamps stay in UTC. */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final Clock clock;
    private static final RowMapper<RagIngestionTask> ROW = (row, index) -> new RagIngestionTask(
            row.getString("task_id"), row.getString("owner_user_id"), row.getString("rag_id"), row.getString("knowledge_tag"),
            row.getString("file_name"), row.getString("file_hash"), row.getLong("file_size"), row.getString("source_path"),
            row.getString("dedup_key"), row.getString("status"), row.getString("stage"), row.getInt("progress"), row.getInt("chunk_count"),
            row.getInt("attempt"), row.getString("last_error"), row.getString("lease_token"), time(row.getTimestamp("lease_until")),
            time(row.getTimestamp("created_at")), time(row.getTimestamp("updated_at")), row.getString("resume_fingerprint"));

    @org.springframework.beans.factory.annotation.Autowired
    public RagIngestionRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db) { this(db, Clock.systemUTC()); }
    public RagIngestionRepository(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private static LocalDateTime time(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }

    public RagIngestionTask insert(RagIngestionTask task) {
        try {
            db.update("""
                    INSERT INTO ai_rag_ingestion_task(task_id,owner_user_id,rag_id,knowledge_tag,file_name,file_hash,file_size,
                      source_path,dedup_key,status,stage,progress,chunk_count,attempt,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, task.taskId(), task.owner(), task.ragId(), task.knowledgeTag(), task.fileName(), task.fileHash(),
                    task.fileSize(), task.sourcePath(), task.dedupKey(), task.status(), task.stage(), task.progress(), 0, 0, now(), now());
        } catch (DuplicateKeyException duplicate) {
            List<RagIngestionTask> existing = db.query("SELECT * FROM ai_rag_ingestion_task WHERE owner_user_id=? AND dedup_key=?", ROW,
                    task.owner(), task.dedupKey());
            if (!existing.isEmpty()) return existing.get(0);
            throw duplicate;
        }
        return owned(task.owner(), task.taskId());
    }

    public RagIngestionTask owned(String owner, String id) {
        List<RagIngestionTask> found = db.query("SELECT * FROM ai_rag_ingestion_task WHERE owner_user_id=? AND task_id=?", ROW, owner, id);
        return found.isEmpty() ? null : found.get(0);
    }

    public List<RagIngestionTask> list(String owner) {
        return db.query("SELECT * FROM ai_rag_ingestion_task WHERE owner_user_id=? AND status<>'DELETED' ORDER BY updated_at DESC LIMIT 100", ROW, owner);
    }

    public RagIngestionTask forFile(String owner, String fileId) {
        List<RagIngestionTask> found = db.query("SELECT * FROM ai_rag_ingestion_task WHERE owner_user_id=? AND rag_id=? AND status<>'DELETED' ORDER BY created_at DESC LIMIT 1", ROW, owner, fileId);
        return found.isEmpty() ? null : found.get(0);
    }

    public List<Map<String, Object>> files(String owner) {
        return db.queryForList("""
                SELECT rag_id AS ragId,rag_name AS fileName,knowledge_tag AS knowledgeTag,file_size AS fileSize,
                  status,create_time AS createdAt FROM ai_client_rag_order
                WHERE user_id=? ORDER BY create_time DESC LIMIT 200
                """, owner);
    }

    public Map<String, Object> ownedFile(String owner, String id) {
        List<Map<String, Object>> found = db.queryForList("""
                SELECT id,rag_id AS ragId,rag_name AS fileName,knowledge_tag AS knowledgeTag,file_size AS fileSize,user_id AS owner
                FROM ai_client_rag_order WHERE user_id=? AND rag_id=?
                """, owner, id);
        return found.isEmpty() ? null : found.get(0);
    }

    public String existingFileId(String owner, String knowledge, String hash) {
        List<String> found = db.query("SELECT rag_id FROM ai_client_rag_order WHERE user_id=? AND knowledge_tag=? AND file_hash=? LIMIT 1",
                (row, index) -> row.getString(1), owner, knowledge, hash);
        return found.isEmpty() ? null : found.get(0);
    }

    public boolean ownsKnowledge(String owner, String knowledge) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM ai_client_rag_order WHERE user_id=? AND knowledge_tag=? AND status=1", Integer.class, owner, knowledge);
        return count != null && count > 0;
    }

    public RagIngestionTask claimNext() {
        List<RagIngestionTask> queued = db.query("SELECT * FROM ai_rag_ingestion_task WHERE status='QUEUED' ORDER BY created_at LIMIT 1", ROW);
        if (queued.isEmpty()) return null;
        RagIngestionTask task = queued.get(0);
        String token = UUID.randomUUID().toString();
        int claimed = db.update("""
                UPDATE ai_rag_ingestion_task SET status='PROCESSING',stage='VERIFYING',attempt=attempt+1,
                  lease_token=?,lease_until=?,updated_at=?,last_error=NULL WHERE task_id=? AND status='QUEUED'
                """, token, now().plusMinutes(2), now(), task.taskId());
        return claimed == 1 ? owned(task.owner(), task.taskId()) : null;
    }

    public boolean progress(RagIngestionTask task, String stage, int progress, int chunks) {
        return db.update("""
                UPDATE ai_rag_ingestion_task SET stage=?,progress=?,chunk_count=CASE WHEN ? < 0 THEN chunk_count ELSE ? END,
                  lease_until=?,updated_at=? WHERE task_id=? AND owner_user_id=? AND lease_token=? AND status='PROCESSING'
                """, stage, Math.min(99, Math.max(0, progress)), chunks, chunks, now().plusMinutes(2), now(), task.taskId(), task.owner(), task.leaseToken()) == 1;
    }

    public void heartbeat(RagIngestionTask task) {
        db.update("""
                UPDATE ai_rag_ingestion_task SET lease_until=? WHERE task_id=? AND owner_user_id=? AND lease_token=?
                AND status IN ('PROCESSING','CANCEL_REQUESTED')
                """, now().plusMinutes(2), task.taskId(), task.owner(), task.leaseToken());
    }

    public boolean complete(RagIngestionTask task) { return complete(task, null); }

    /** A succeeded task keeps an optional quality warning in last_error; the view exposes it as a warning, not an error. */
    public boolean complete(RagIngestionTask task, String warning) {
        return db.update("""
                UPDATE ai_rag_ingestion_task SET status='SUCCEEDED',stage='SUCCEEDED',progress=100,last_error=?,
                  lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE task_id=? AND owner_user_id=? AND lease_token=? AND status='PROCESSING'
                """, warning, now(), task.taskId(), task.owner(), task.leaseToken()) == 1;
    }

    public void fail(RagIngestionTask task, String message) {
        db.update("""
                UPDATE ai_rag_ingestion_task SET status='FAILED',last_error=?,lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE task_id=? AND owner_user_id=? AND lease_token=? AND status='PROCESSING'
                """, message, now(), task.taskId(), task.owner(), task.leaseToken());
    }

    /** Records which split / enrichment the running attempt writes, so a later retry can reuse its completed parents. */
    public boolean saveFingerprint(RagIngestionTask task, String fingerprint) {
        return db.update("""
                UPDATE ai_rag_ingestion_task SET resume_fingerprint=?,lease_until=?,updated_at=?
                WHERE task_id=? AND owner_user_id=? AND lease_token=? AND status='PROCESSING'
                """, fingerprint, now().plusMinutes(2), now(), task.taskId(), task.owner(), task.leaseToken()) == 1;
    }

    public boolean retry(String owner, String id) {
        // retry_day_count is assigned before retry_day, so it still compares against the previous day.
        LocalDate day = quotaDay();
        return db.update("""
                UPDATE ai_rag_ingestion_task SET status='QUEUED',stage='QUEUED',progress=0,last_error=NULL,updated_at=?,
                  retry_day_count=CASE WHEN retry_day=? THEN retry_day_count+1 ELSE 1 END,retry_day=?
                WHERE task_id=? AND owner_user_id=? AND status='FAILED' AND lease_token IS NULL
                """, now(), day, day, id, owner) == 1;
    }

    public record Usage(int files, long bytes, int today, int pending) { }

    /**
     * Files and bytes count published files plus unfinished tasks that still hold a retained source.
     * Today counts new submissions (excluding instant duplicates) and retries since 00:00 Beijing time.
     */
    public Usage usage(String owner) {
        Map<String, Long> sizes = new HashMap<>();
        db.query("SELECT rag_id,file_size FROM ai_client_rag_order WHERE user_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> sizes.put(row.getString(1), row.getLong(2)), owner);
        int pending = 0;
        for (Map<String, Object> row : db.queryForList("""
                SELECT rag_id,file_size,status FROM ai_rag_ingestion_task WHERE owner_user_id=?
                AND status IN ('QUEUED','PROCESSING','CANCEL_REQUESTED','FAILED','DELETING','DELETE_FAILED')
                """, owner)) {
            sizes.putIfAbsent(String.valueOf(row.get("rag_id")), ((Number) row.get("file_size")).longValue());
            if (List.of("QUEUED", "PROCESSING", "CANCEL_REQUESTED").contains(String.valueOf(row.get("status")))) pending++;
        }
        Integer submitted = db.queryForObject("""
                SELECT COUNT(*) FROM ai_rag_ingestion_task WHERE owner_user_id=? AND created_at>=?
                AND NOT (status='SUCCEEDED' AND attempt=0)
                """, Integer.class, owner, quotaDay().atStartOfDay(QUOTA_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        Integer retried = db.queryForObject("SELECT COALESCE(SUM(retry_day_count),0) FROM ai_rag_ingestion_task WHERE owner_user_id=? AND retry_day=?",
                Integer.class, owner, quotaDay());
        return new Usage(sizes.size(), sizes.values().stream().mapToLong(Long::longValue).sum(),
                (submitted == null ? 0 : submitted) + (retried == null ? 0 : retried), pending);
    }

    private LocalDate quotaDay() { return clock.instant().atZone(QUOTA_ZONE).toLocalDate(); }

    public boolean requestCancel(String owner, String id) {
        return db.update("""
                UPDATE ai_rag_ingestion_task SET status='CANCEL_REQUESTED',stage='CANCELLING',updated_at=?
                WHERE task_id=? AND owner_user_id=? AND status='PROCESSING'
                """, now(), id, owner) == 1;
    }

    /** Only a returning worker may release its own cancellation lease. Cleanup never races that worker. */
    public void releaseCancelled(RagIngestionTask task) {
        db.update("""
                UPDATE ai_rag_ingestion_task SET status='DELETE_FAILED',last_error='入库已停止，请点击删除清理已写入的片段和原件。',
                  lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE task_id=? AND owner_user_id=? AND lease_token=? AND status='CANCEL_REQUESTED'
                """, now(), task.taskId(), task.owner(), task.leaseToken());
    }

    public boolean beginDelete(String owner, String id) {
        return db.update("""
                UPDATE ai_rag_ingestion_task SET status='DELETING',stage='DELETING',updated_at=?
                WHERE task_id=? AND owner_user_id=? AND status IN ('QUEUED','FAILED','SUCCEEDED','DELETE_FAILED') AND lease_token IS NULL
                """, now(), id, owner) == 1;
    }

    public void deleteFailed(String owner, String id, String message) {
        db.update("UPDATE ai_rag_ingestion_task SET status='DELETE_FAILED',last_error=?,updated_at=? WHERE task_id=? AND owner_user_id=? AND status='DELETING'",
                message, now(), id, owner);
    }

    public void deleted(String owner, String id) {
        db.update("""
                UPDATE ai_rag_ingestion_task SET status='DELETED',stage='DELETED',dedup_key=NULL,last_error=NULL,updated_at=?
                WHERE task_id=? AND owner_user_id=? AND status='DELETING'
                """, now(), id, owner);
    }

    /** No automatic model retries on restart. Recovery preserves sources and requires an explicit user action. */
    public void recoverExpired() {
        db.update("""
                UPDATE ai_rag_ingestion_task SET status='FAILED',last_error='入库进程中断，原件已保留；请重试或删除。',
                  lease_token=NULL,lease_until=NULL,updated_at=? WHERE status='PROCESSING' AND lease_until < ?
                """, now(), now());
        db.update("""
                UPDATE ai_rag_ingestion_task SET status='DELETE_FAILED',last_error='取消入库后进程中断，请重新删除以完成清理。',
                  lease_token=NULL,lease_until=NULL,updated_at=? WHERE status='CANCEL_REQUESTED' AND lease_until < ?
                """, now(), now());
        db.update("""
                UPDATE ai_rag_ingestion_task SET status='DELETE_FAILED',last_error='清理进程中断，请重试删除。',updated_at=?
                WHERE status='DELETING' AND updated_at < ?
                """, now(), now().minusMinutes(10));
    }
}
