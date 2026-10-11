package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.notification.AgentNotification;
import cn.bugstack.ai.domain.agent.service.notification.NotificationCommand;
import cn.bugstack.ai.domain.agent.service.notification.NotificationService;
import cn.bugstack.ai.domain.agent.service.notification.RunNotificationProjector;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

public class RunNotificationProjectorTest {

    @Test
    public void shouldProjectAskUserAndResolveTimeout() {
        NotificationService notifications = mock(NotificationService.class);
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find("run-1")).thenReturn(Optional.of(RunSnapshot.builder()
                .runId("run-1")
                .sessionId("session-1")
                .userId("user-1")
                .originalMessage("请帮我发布本周项目进度")
                .build()));

        RunNotificationProjector projector = new RunNotificationProjector(notifications);
        ReflectionTestUtils.setField(projector, "runSnapshotService", snapshots);
        projector.project("run-1", "session-1", "user_input_required", """
                {"inputId":"input-1","step":"Step2","context":"缺少平台",\
                "questions":["要发布到哪个平台？"],"expiresAt":123456789}
                """);

        ArgumentCaptor<NotificationCommand> command = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notifications).publish(command.capture());
        assertEquals("user-1", command.getValue().getUserId());
        assertEquals("input-1", command.getValue().getReferenceId());
        assertEquals(AgentNotification.Type.ASK_USER, command.getValue().getType());
        assertEquals("要发布到哪个平台？", command.getValue().getSummary());
        assertNotNull(command.getValue().getMetadata().get("questions"));

        projector.project("run-1", "session-1", "user_input_result",
                "{\"inputId\":\"input-1\",\"status\":\"TIMEOUT\"}");
        verify(notifications).resolve(eq("user-1"), eq("input-1"), eq(AgentNotification.Status.EXPIRED));
    }

    @Test
    public void shouldProjectToolApproval() {
        NotificationService notifications = mock(NotificationService.class);
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find("run-2")).thenReturn(Optional.of(RunSnapshot.builder()
                .runId("run-2").sessionId("session-2").userId("user-2").build()));
        RunNotificationProjector projector = new RunNotificationProjector(notifications);
        ReflectionTestUtils.setField(projector, "runSnapshotService", snapshots);

        projector.project("run-2", "session-2", "human_approval_required", """
                {"approvalId":"approval-1","toolName":"publish_post",\
                "toolInput":"{target:github}","expiresAt":123456789}
                """);

        ArgumentCaptor<NotificationCommand> command = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notifications).publish(command.capture());
        assertEquals(AgentNotification.Type.TOOL_APPROVAL, command.getValue().getType());
        assertEquals("approval-1", command.getValue().getReferenceId());
        assertEquals("publish_post", command.getValue().getMetadata().get("toolName"));
    }

    @Test
    public void terminalEventsUseOneStableOwnedCardWithoutReplacingPlanReference() {
        NotificationService notifications = mock(NotificationService.class);
        RunNotificationProjector projector = terminalProjector(notifications, "RUNNING");
        projector.project("r1", "s1", "message", "{\"type\":\"complete\",\"completed\":true}");
        projector.project("r1", "s1", "plan_review_status", "{\"status\":\"COMPLETED\"}");
        ArgumentCaptor<NotificationCommand> commands = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notifications, times(2)).publish(commands.capture());
        NotificationCommand first = commands.getAllValues().get(0), replay = commands.getAllValues().get(1);
        assertEquals(NotificationService.terminalId("u1", "r1"), first.getNotificationId());
        assertEquals(first.getNotificationId(), replay.getNotificationId());
        assertEquals("run-terminal:r1", first.getReferenceId());
        assertEquals("u1", first.getUserId());
        assertEquals("s1", first.getSessionId());
        assertEquals(AgentNotification.Type.TASK_COMPLETED, first.getType());
        assertFalse(first.getMetadata().containsKey("answer"));
    }

    @Test
    public void stepErrorsSummaryAndPlanWaitDoNotCreateTerminalNotifications() {
        NotificationService notifications = mock(NotificationService.class);
        RunNotificationProjector projector = terminalProjector(notifications, "BLOCKED");
        projector.project("r1", "s1", "message", "{\"type\":\"error\",\"step\":2,\"content\":\"tool failed\"}");
        projector.project("r1", "s1", "message", "{\"type\":\"summary\",\"completed\":true}");
        projector.project("r1", "s1", "message", "{\"type\":\"complete\",\"completed\":false}");
        projector.project("r1", "s1", "plan_review_status", "{\"status\":\"RUNNING\"}");
        projector.project("r1", "s1", "plan_review_status", "{\"status\":\"CANCELLED\"}");
        verifyNoInteractions(notifications);
    }

    @Test
    public void terminalPreviewDoesNotCopyRawProviderErrorAndUsesFailureCategory() {
        NotificationService notifications = mock(NotificationService.class);
        RunNotificationProjector projector = terminalProjector(notifications, "FAILED");
        projector.project("r1", "s1", "message", "{\"type\":\"error\",\"content\":\"TimeoutException token=sk-secret https://private.example\"}");
        ArgumentCaptor<NotificationCommand> commands = ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notifications).publish(commands.capture());
        assertEquals(AgentNotification.Type.TASK_FAILED, commands.getValue().getType());
        assertTrue(commands.getValue().getSummary().contains("超时"));
        assertFalse(commands.getValue().getSummary().contains("sk-secret"));
        assertFalse(commands.getValue().getMetadata().toString().contains("private.example"));
    }

    @Test
    public void terminalNotificationRequiresOwnedSnapshotAndCorrectSession() {
        NotificationService notifications = mock(NotificationService.class);
        RunNotificationProjector projector = terminalProjector(notifications, "RUNNING");
        projector.project("r1", "foreign-session", "message", "{\"type\":\"complete\",\"completed\":true}");
        org.slf4j.MDC.put("userId", "intruder");
        try {
            projector.project("missing-run", "s1", "message", "{\"type\":\"complete\",\"completed\":true}");
        } finally { org.slf4j.MDC.remove("userId"); }
        verifyNoInteractions(notifications);
    }

    @Test
    public void failedOrCancelledSnapshotCannotBeTurnedIntoSuccessByLateComplete() {
        NotificationService notifications = mock(NotificationService.class);
        RunNotificationProjector failed = terminalProjector(notifications, "FAILED");
        failed.project("r1", "s1", "message", "{\"type\":\"complete\",\"completed\":true}");
        RunNotificationProjector cancelled = terminalProjector(notifications, "CANCELLED");
        cancelled.project("r1", "s1", "message", "{\"type\":\"complete\",\"completed\":true}");
        verify(notifications, org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.any());
    }

    private RunNotificationProjector terminalProjector(NotificationService notifications, String status) {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find("r1")).thenReturn(Optional.of(RunSnapshot.builder().runId("r1")
                .sessionId("s1").userId("u1").status(status).originalMessage("北京三日旅行攻略").build()));
        RunNotificationProjector projector = new RunNotificationProjector(notifications);
        ReflectionTestUtils.setField(projector, "runSnapshotService", snapshots);
        return projector;
    }
}
