package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.event.RunPreparationProgress;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RunPreparationProgressTest {
    @Test
    public void stagesHeartbeatAndTerminalCleanupStayBoundToTheirRun() {
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        List<Map<String, Object>> events = new ArrayList<>();
        doAnswer(call -> { events.add(Map.copyOf(call.getArgument(3))); return null; })
                .when(publisher).publish(eq("run-a"), eq("session-a"), eq("run_progress"), any());
        RunPreparationProgress progress = new RunPreparationProgress(publisher);
        progress.start("run-a", "session-a");
        progress.phase("run-a", "agent_routing");
        MDC.put("runId", "run-a");
        try (var ignored = progress.connecting("天气服务")) {
            progress.heartbeat();
            assertEquals("mcp_connecting", events.get(events.size() - 1).get("phase"));
            assertEquals("天气服务", events.get(events.size() - 1).get("resourceName"));
            assertEquals("run-a", events.get(events.size() - 1).get("runId"));
            assertFalse(events.get(events.size() - 1).containsKey("mcpId"));
        } finally { MDC.remove("runId"); }
        assertEquals("agent_preparing", events.get(events.size() - 1).get("phase"));
        progress.ready("run-a");
        assertEquals("run_ready", events.get(events.size() - 1).get("phase"));
        int size = events.size();
        progress.heartbeat();
        progress.close("run-a");
        assertEquals(size, events.size());
        verify(publisher, never()).publish(anyString(), anyString(), eq("step_progress"), any());
    }

    @Test
    public void closePreventsLateScopeAndHeartbeatFromResurrectingProgress() {
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        RunPreparationProgress progress = new RunPreparationProgress(publisher);
        progress.start("run-b", "session-b");
        MDC.put("runId", "run-b");
        RunPreparationProgress.Scope connection;
        try { connection = progress.connecting("连接"); } finally { MDC.remove("runId"); }
        progress.close("run-b");
        clearInvocations(publisher);
        connection.close();
        progress.heartbeat();
        try (var ignored = progress.connecting("no run")) { }
        verifyNoInteractions(publisher);
    }
}
