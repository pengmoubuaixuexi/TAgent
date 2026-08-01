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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
