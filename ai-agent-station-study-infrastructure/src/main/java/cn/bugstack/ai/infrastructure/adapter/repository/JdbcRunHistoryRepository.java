package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable run metadata and bounded diagnostics. A lost worker is never shown as still running. */
@Repository
@Slf4j
public class JdbcRunHistoryRepository implements IRunHistoryRepository {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","INTERRUPTED");
    private static final Set<String> STATUSES=Set.of("RUNNING","BLOCKED","COMPLETED","FAILED","CANCELLED","INTERRUPTED");
    // Retain the indexed predicate, then require exact identities even under a CI/PAD SPACE collation.
    private static final String EXACT_USER="user_id=? AND CAST(user_id AS BINARY)=CAST(? AS BINARY) AND CHAR_LENGTH(user_id)=CHAR_LENGTH(?)";
    private static final String EXACT_SESSION="session_id=? AND CAST(session_id AS BINARY)=CAST(? AS BINARY) AND CHAR_LENGTH(session_id)=CHAR_LENGTH(?)";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String instance=UUID.randomUUID().toString();
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private record StatusUpdate(String status,String error){}
    private final Map<String,StatusUpdate> pendingStatuses=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Value("${agent.run-budget.max-seconds:1800}")
    private long maxRunSeconds=1800;
    public JdbcRunHistoryRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate db,ObjectMapper json) { this.db=db;this.json=json; }
    @Override public void start(ExecuteCommandEntity r) {
        require(r.getUserId());require(r.getRunId());require(r.getSessionId());
        // Duplicate IDs are rejected by the database, including after a restart or Redis expiry.
        db.update("INSERT INTO ai_run_history(run_id,user_id,session_id,status,question,instance_id) VALUES(?,?,?,'RUNNING',?,?)",
                r.getRunId(),r.getUserId(),r.getSessionId(),clip(r.getMessage(),262144),instance);
        active.add(r.getRunId());
    }
    @Override public void bindAgent(ExecuteCommandEntity r,String strategy) {
        db.update("UPDATE ai_run_history SET agent_id=?,agent_version=?,strategy=?,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=? AND user_id=? AND status='RUNNING'",
                r.getAiAgentId(),r.getAgentVersion(),strategy,r.getRunId(),r.getUserId());
    }
    @Override public void checkpoint(String runId,List<RunEventRecord> events) {
        List<Map<String,Object>> saved=new ArrayList<>(); int length=0; boolean truncated=false;
        // Keep the newest diagnostics within a fixed storage bound; the authoritative final answer is separate.
        for(int i=events.size()-1;i>=0;i--) {
            RunEventRecord e=events.get(i); String payload=e.getPayloadJson()==null?"":e.getPayloadJson();
            boolean clipped=payload.length()>32768;
            Map<String,Object> row=new LinkedHashMap<>();row.put("eventId",e.getEventId());row.put("eventType",e.getEventType());row.put("createdAt",e.getCreatedAt());
            if(clipped) {row.put("payload",Map.of("preview",clip(payload,32768),"truncated",true));truncated=true;}
            else {try {row.put("payload",json.readTree(payload));}catch(Exception malformed){row.put("payload",Map.of("preview",payload));}}
            int size=encode(row).length();
            if(saved.size()>=2000 || length+size>1048576) {truncated=true;break;}
            saved.add(row);length+=size;
        }
        Collections.reverse(saved);
        db.update("UPDATE ai_run_history SET events_json=?,events_truncated=?,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=? AND instance_id=?",
                encode(saved),truncated?1:0,runId,instance);
    }
    @Override public synchronized void status(String runId,String status,String error) {
        if(!STATUSES.contains(status)) return;
        if(TERMINAL.contains(status)||"BLOCKED".equals(status)) active.remove(runId);
        StatusUpdate update=new StatusUpdate(status,clip(error,4000));pendingStatuses.put(runId,update);
        persistStatus(runId,update);
    }
    private synchronized void persistStatus(String runId,StatusUpdate update) {
        if(pendingStatuses.get(runId)!=update)return;
        String status=update.status();
        // An old completion/cancel event cannot overwrite an already terminal result.
        int updated=db.update("UPDATE ai_run_history SET status=?,last_error=?,updated_at=CURRENT_TIMESTAMP(3),heartbeat_at=CURRENT_TIMESTAMP(3) WHERE run_id=? AND status IN ('RUNNING','BLOCKED')",
                status,update.error(),runId);
        if(updated>0&&"RUNNING".equals(status)) {
            db.update("UPDATE ai_run_history SET instance_id=? WHERE run_id=? AND status='RUNNING'",instance,runId);
            active.add(runId);
        }
        pendingStatuses.remove(runId,update);
    }
    @Override public void answer(String user,String run,String value) {
        if(user==null||run==null||value==null)return;
        db.update("UPDATE ai_run_history SET answer=?,updated_at=CURRENT_TIMESTAMP(3) WHERE run_id=? AND user_id=? AND answer IS NULL",clip(value,1048576),run,user);
    }
    @Override public Optional<Map<String,Object>> findOwned(String user,String run) {
        require(user);require(run);
        return db.queryForList("SELECT * FROM ai_run_history WHERE "+EXACT_USER+" AND run_id=? AND CAST(run_id AS BINARY)=CAST(? AS BINARY) AND CHAR_LENGTH(run_id)=CHAR_LENGTH(?)",
                user,user,user,run,run,run).stream().findFirst().map(this::view);
    }
    @Override public List<Map<String,Object>> listOwned(String user,int offset,int limit) {
        require(user);
        return db.queryForList("SELECT run_id,user_id,agent_id,agent_version,strategy,status,LEFT(question,300) question,last_error,created_at,updated_at,heartbeat_at FROM ai_run_history WHERE "+EXACT_USER+" ORDER BY created_at DESC,run_id DESC LIMIT ? OFFSET ?",
                user,user,user,boundedLimit(limit),boundedOffset(offset)).stream().map(this::view).toList();
    }
    @Override public List<Map<String,Object>> listOwnedSession(String user,String session,int offset,int limit) {
        require(user);require(session);
        return db.queryForList("SELECT run_id,user_id,session_id,agent_id,agent_version,strategy,status,LEFT(question,300) question,last_error,created_at,updated_at,heartbeat_at FROM ai_run_history WHERE "+EXACT_USER+" AND "+EXACT_SESSION+" ORDER BY created_at DESC,run_id DESC LIMIT ? OFFSET ?",
                user,user,user,session,session,session,boundedLimit(limit),boundedOffset(offset)).stream().map(this::view).toList();
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        Map<String,String> names=Map.ofEntries(Map.entry("run_id","runId"),Map.entry("user_id","userId"),Map.entry("session_id","sessionId"),Map.entry("agent_id","agentId"),Map.entry("agent_version","agentVersion"),Map.entry("last_error","error"),Map.entry("created_at","createdAt"),Map.entry("updated_at","updatedAt"),Map.entry("events_truncated","eventsTruncated"));
        for(var e:row.entrySet()) {
            String key=e.getKey().toLowerCase(Locale.ROOT);
            if(Set.of("instance_id","heartbeat_at").contains(key)) continue;
            Object value=e.getValue();if(value instanceof Timestamp timestamp)value=timestamp.toInstant().toString();
            if(value instanceof java.sql.Clob clob)try{value=clob.getSubString(1,(int)clob.length());}catch(java.sql.SQLException ex){throw new IllegalStateException(ex);}
            if("events_json".equals(key)){try{result.put("events",value==null?List.of():json.readTree(value.toString()));}catch(Exception ignored){result.put("events",List.of());}continue;}
            result.put(names.getOrDefault(key,key),value);
        }
        Object heartbeat=row.get("heartbeat_at");
        if("RUNNING".equals(result.get("status"))&&heartbeat instanceof Timestamp t&&t.getTime()<System.currentTimeMillis()-180000) {
            result.put("status","INTERRUPTED"); result.put("error","执行进程心跳已过期；任务未自动重跑，请确认外部操作结果后重新发起。");
        }
        return result;
    }
    @Scheduled(fixedDelay=30000) public void heartbeat() {
        try {
            for(var pending:pendingStatuses.entrySet())persistStatus(pending.getKey(),pending.getValue());
            for(String run:active) db.update("UPDATE ai_run_history SET heartbeat_at=CURRENT_TIMESTAMP(3) WHERE run_id=? AND instance_id=? AND status='RUNNING'",run,instance);
            db.update("UPDATE ai_run_history SET status='INTERRUPTED',last_error='Worker heartbeat expired; execution was not replayed',updated_at=CURRENT_TIMESTAMP(3) WHERE status='RUNNING' AND heartbeat_at<?",new Timestamp(System.currentTimeMillis()-180000));
            // Human review consumes the same absolute run deadline; after restart there is no in-memory
            // budget to resume. Keep historical waiting states from claiming indefinite resumability.
            db.update("UPDATE ai_run_history SET status='INTERRUPTED',last_error='Plan review exceeded the run deadline; generate a fresh plan',updated_at=CURRENT_TIMESTAMP(3) WHERE status='BLOCKED' AND created_at<?",
                    new Timestamp(System.currentTimeMillis()-Math.max(1,maxRunSeconds)*1000L));
        } catch(RuntimeException e) {log.warn("[RunHistory] heartbeat persistence unavailable: {}",e.getClass().getSimpleName());}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("运行记录序列化失败",e);}}
    private static String clip(String s,int length){return s==null?null:s.substring(0,Math.min(s.length(),length));}
    private static int boundedLimit(int limit){return Math.max(1,Math.min(100,limit));}
    private static int boundedOffset(int offset){return Math.max(0,Math.min(100000,offset));}
    private static void require(String s){if(s==null||s.isBlank())throw new IllegalArgumentException("缺少运行归属信息");}
}
