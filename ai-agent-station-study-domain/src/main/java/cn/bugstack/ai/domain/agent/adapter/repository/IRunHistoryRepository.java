package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import java.util.*;

/** Durable owner-scoped history, independent of Redis replay and tool-result TTLs. */
public interface IRunHistoryRepository {
    void start(ExecuteCommandEntity request);
    void bindAgent(ExecuteCommandEntity request,String strategy);
    void checkpoint(String runId,List<RunEventRecord> compactEvents);
    void status(String runId,String status,String error);
    void answer(String userId,String runId,String answer);
    Optional<Map<String,Object>> findOwned(String userId,String runId);
    List<Map<String,Object>> listOwned(String userId,int offset,int limit);
    /** Bounded metadata only, for discovering runs when chat memory or Redis replay is absent. */
    List<Map<String,Object>> listOwnedSession(String userId,String sessionId,int offset,int limit);
}
