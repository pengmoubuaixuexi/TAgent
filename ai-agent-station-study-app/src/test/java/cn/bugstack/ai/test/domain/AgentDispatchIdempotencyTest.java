package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.dispatch.RunDispatchConflictException;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentDispatchIdempotencyTest {

    @Test
    public void sameActiveRunIdIsAnIdempotentJoinNotASecondExecution() throws Exception {
        AgentDispatchDispatchService service = new AgentDispatchDispatchService();
        activeRuns(service).put("session-1", "run-1");

        service.dispatch(command("session-1", "run-1"), new ResponseBodyEmitter());

        assertEquals("run-1", service.activeRunId("session-1"));
    }

    @Test
    public void differentRunIdReportsTypedSessionBusyConflict() {
        AgentDispatchDispatchService service = new AgentDispatchDispatchService();
        activeRuns(service).put("session-1", "run-existing");

        try {
            service.dispatch(command("session-1", "run-new"), new ResponseBodyEmitter());
            fail("expected session busy conflict");
        } catch (RunDispatchConflictException error) {
            assertEquals(RunDispatchConflictException.Reason.SESSION_BUSY, error.getReason());
            assertEquals("run-existing", error.getExistingRunId());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void terminalSnapshotPreventsRunIdReuseAfterLeaseWasReleased() {
        AgentDispatchDispatchService service = new AgentDispatchDispatchService();
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        ReflectionTestUtils.setField(service, "runSnapshotService", snapshots);
        when(snapshots.find("run-used")).thenReturn(Optional.of(RunSnapshot.builder()
                .runId("run-used")
                .sessionId("session-1")
                .status(RunSnapshotService.STATUS_COMPLETED)
                .build()));

        try {
            service.dispatch(command("session-1", "run-used"), new ResponseBodyEmitter());
            fail("expected duplicate runId conflict");
        } catch (RunDispatchConflictException error) {
            assertEquals(RunDispatchConflictException.Reason.DUPLICATE_RUN_ID, error.getReason());
            assertNull(service.activeRunId("session-1"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, String> activeRuns(AgentDispatchDispatchService service) {
        return (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(service, "activeRunBySession");
    }

    private static ExecuteCommandEntity command(String sessionId, String runId) {
        return ExecuteCommandEntity.builder()
                .sessionId(sessionId)
                .runId(runId)
                .message("test")
                .build();
    }
}
