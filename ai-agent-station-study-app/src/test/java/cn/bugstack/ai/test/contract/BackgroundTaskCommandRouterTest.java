package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.trigger.background.BackgroundTaskCommand;
import cn.bugstack.ai.trigger.background.BackgroundTaskCommandRouter;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BackgroundTaskCommandRouterTest {

    @Test
    public void ordinaryAgentQuestionDoesNotEnterPlatformRouter() {
        BackgroundTaskCommandRouter router = new BackgroundTaskCommandRouter();
        assertFalse(router.mightBeBackgroundTask("帮我解释一下 Redis Stream 的消费组"));
        assertTrue(router.mightBeBackgroundTask("每天早上 9 点检查一次项目状态"));
        assertTrue(router.mightBeBackgroundTask("暂停 v6.md 监视器"));
    }

    @Test
    public void parsesStrictCreateSchemaAndKeepsActionPrompt() {
        BackgroundTaskCommandRouter router = new BackgroundTaskCommandRouter();
        BackgroundTaskCommand command = router.parse("""
                {
                  "matched": true,
                  "confidence": 0.96,
                  "operation": "CREATE",
                  "needs_clarification": false,
                  "clarifying_questions": [],
                  "task_reference": null,
                  "task_draft": {
                    "task_type": "FILE_CHANGE_STABLE",
                    "name": "Watch v6",
                    "trigger": {"path":"v6.md","quiet_seconds":120},
                    "action_prompt":"Read v6.md and review Claude's new reply",
                    "action_agent_id":null,
                    "max_step":5,
                    "run_once":true
                  }
                }
                """);
        assertNotNull(command);
        assertTrue(command.isMatched());
        assertEquals("CREATE", command.getOperation());
        assertEquals("FILE_CHANGE_STABLE", command.getTaskDraft().getTaskType());
        assertEquals(120, ((Number) command.getTaskDraft().getTrigger().get("quiet_seconds")).intValue());
        assertEquals("Read v6.md and review Claude's new reply", command.getTaskDraft().getActionPrompt());
    }

    @Test
    public void explicitFileMonitorHasDeterministicFallbackWhenLlmUnavailable() {
        BackgroundTaskCommandRouter router = new BackgroundTaskCommandRouter();
        BackgroundTaskCommand command = router.route(
                "创建对象监视器监视 v6.md，改动后 2 分钟没有变化就读取 Claude 的回复",
                "session-test");
        assertTrue(command.isMatched());
        assertFalse(command.isNeedsClarification());
        assertEquals("FILE_CHANGE_STABLE", command.getTaskDraft().getTaskType());
        assertEquals(120, ((Number) command.getTaskDraft().getTrigger().get("quiet_seconds")).intValue());
        assertTrue(command.getTaskDraft().getActionPrompt().contains("Claude"));
    }
}
