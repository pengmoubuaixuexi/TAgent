package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.trigger.http.AiAgentController;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AiAgentControllerRunReconnectTest {

    @Test
    public void reconnectDoubleChecksTerminalStatusAfterAttach() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        AiAgentController controller = controller(snapshots, publisher, dispatch);
        RunSnapshot running = snapshot("run-1", "session-1", RunSnapshotService.STATUS_RUNNING);
        RunSnapshot completed = snapshot("run-1", "session-1", RunSnapshotService.STATUS_COMPLETED);
        when(snapshots.find("run-1"))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(completed));
        when(dispatch.activeRunId("session-1")).thenReturn("run-1");

        controller.reconnectRunStream(
                "run-1", "session-1", null, mock(HttpServletResponse.class));

        verify(publisher).attach(eq("run-1"), eq("session-1"), any(), eq(null));
        verify(publisher).finishRun("run-1");
    }

    @Test
    public void activeRunEndpointMarksRestartOrphanFailedAndHidesIt() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        RunEventPublisher publisher = mock(RunEventPublisher.class);
        IAgentDispatchService dispatch = mock(IAgentDispatchService.class);
        AiAgentController controller = controller(snapshots, publisher, dispatch);
        RunSnapshot running = snapshot("run-orphan", "session-1", RunSnapshotService.STATUS_RUNNING);
        when(snapshots.listRecent("session-1", 20)).thenReturn(List.of(running));
        when(dispatch.activeRunId("session-1")).thenReturn(null);

        Response<RunSnapshot> response = controller.getActiveRun("session-1");

        assertNull(response.getData());
        verify(snapshots).markStatus(
                eq("run-orphan"), eq(RunSnapshotService.STATUS_FAILED), any());
    }

    private static AiAgentController controller(RunSnapshotService snapshots,
                                                RunEventPublisher publisher,
                                                IAgentDispatchService dispatch) {
        AiAgentController controller = new AiAgentController();
        ReflectionTestUtils.setField(controller, "runSnapshotService", snapshots);
        ReflectionTestUtils.setField(controller, "runEventPublisher", publisher);
        ReflectionTestUtils.setField(controller, "agentDispatchService", dispatch);
        return controller;
    }

    private static RunSnapshot snapshot(String runId, String sessionId, String status) {
        return RunSnapshot.builder()
                .runId(runId)
                .sessionId(sessionId)
                .status(status)
                .build();
    }
}
