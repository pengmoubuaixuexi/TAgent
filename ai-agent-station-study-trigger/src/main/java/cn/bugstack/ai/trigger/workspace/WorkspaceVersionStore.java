package cn.bugstack.ai.trigger.workspace;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Called within the same transaction as the graph change. Versions are never updated in place. */
@Component
public class WorkspaceVersionStore {
    private final JdbcTemplate db;
    public WorkspaceVersionStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db) { this.db=db; }
    public void record(String user,String id,int version,String config,String note) {
        var previous=db.queryForList("SELECT config_hash FROM ai_agent_version WHERE agent_id=? AND version=?",String.class,id,version);
        if(!previous.isEmpty()) return; // The caller holds the aggregate row lock.
        db.update("INSERT INTO ai_agent_version(agent_id,version,owner_user_id,config_json,config_hash,note) VALUES(?,?,?,?,?,?)",
                id,version,user,config,hash(config),note);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
