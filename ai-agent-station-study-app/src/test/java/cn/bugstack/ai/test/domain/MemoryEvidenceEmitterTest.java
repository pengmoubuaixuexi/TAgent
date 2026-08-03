package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.MemoryEvidenceEmitter;
import cn.bugstack.ai.domain.agent.service.security.ApprovalChannelRegistry;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryRecall;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * H2-A 单测：MemoryEvidenceEmitter 行为。纯 JUnit4，不拉 Spring。
 */
public class MemoryEvidenceEmitterTest {

    @Test
    public void nullSessionIdSilentlySkips() {
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(new ApprovalChannelRegistry(), true);
        e.emitLongTermEvidence(null, List.of("[skill:Java] 用户会 Java"));
        e.emitLongTermEvidence("  ", List.of("[skill:Java] 用户会 Java"));
        e.emitEpisodicEvidence(null, "summary", List.of("other"));
        // 不抛即通过
    }

    @Test
    public void explainDisabledDoesNotEmit() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, false);

        e.emitLongTermEvidence("s1", List.of("[skill:Java] 用户会 Java"));
        e.emitEpisodicEvidence("s1", "当前会话摘要", List.of("其他会话"));

        assertEquals("explain 关闭时不应 emit", 0, cap.sent.size());
    }

    @Test
    public void emptyProfileLinesDoesNotEmit() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitLongTermEvidence("s1", null);
        e.emitLongTermEvidence("s1", new ArrayList<>());

        assertEquals(0, cap.sent.size());
    }

    @Test
    public void missingChannelSilentlySkips() {
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(new ApprovalChannelRegistry(), true);
        e.emitLongTermEvidence("nonexistent", List.of("[fact:role] 用户是工程师"));
        // 不抛即通过
    }

    @Test
    public void longTermEmitsTopicAndContent() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitLongTermEvidence("s1", List.of(
                "[skill:Java] 用户精通 Java",
                "[fact:role] 用户是后端工程师"));

        assertEquals(1, cap.sent.size());
        String frame = cap.sent.get(0);
        assertTrue(frame.startsWith("event: memory_evidence\n"));
        assertTrue(frame.contains("\"memoryType\":\"long_term\""));
        assertTrue(frame.contains("\"topic\":\"skill:Java\""));
        assertTrue(frame.contains("\"content\":\"用户精通 Java\""));
        assertTrue(frame.contains("\"topic\":\"fact:role\""));
    }

    @Test
    public void longTermLineWithoutBracketFallsBackToOther() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitLongTermEvidence("s1", List.of("没有方括号的纯文本记忆"));

        String frame = cap.sent.get(0);
        assertTrue(frame.contains("\"topic\":\"other\""));
        assertTrue(frame.contains("\"content\":\"没有方括号的纯文本记忆\""));
    }

    @Test
    public void episodicEmitsCurrentAndOther() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitEpisodicEvidence("s1", "本次聊了深蹲动作", List.of("上次聊了卧推", "再上次聊了硬拉"));

        String frame = cap.sent.get(0);
        assertTrue(frame.contains("\"memoryType\":\"episodic\""));
        assertTrue(frame.contains("\"kind\":\"current\""));
        assertTrue(frame.contains("\"content\":\"本次聊了深蹲动作\""));
        assertTrue(frame.contains("\"kind\":\"other\""));
        assertTrue(frame.contains("上次聊了卧推"));
    }

    @Test
    public void episodicAllEmptyDoesNotEmit() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitEpisodicEvidence("s1", null, null);
        e.emitEpisodicEvidence("s1", "  ", new ArrayList<>());

        assertEquals("全空不应 emit", 0, cap.sent.size());
    }

    @Test
    public void episodicOnlyCurrentNoOther() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitEpisodicEvidence("s1", "只有当前会话摘要", null);

        String frame = cap.sent.get(0);
        assertTrue(frame.contains("\"kind\":\"current\""));
        assertFalse(frame.contains("\"kind\":\"other\""));
    }

    @Test
    public void detailedLongTermOnlyShowsSimilarityForRelevantMemory() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter emitter = new MemoryEvidenceEmitter(channels, true);

        emitter.emitLongTermEvidenceDetailed("s1", List.of(
                LongTermMemoryRecall.builder().topic("画像:职业").content("Java工程师")
                        .kind(LongTermMemoryRecall.KIND_CORE).build(),
                LongTermMemoryRecall.builder().topic("技能:Java").content("熟悉JVM")
                        .kind(LongTermMemoryRecall.KIND_RELEVANT).similarity(0.82d).build()));

        String frame = cap.sent.get(0);
        assertTrue(frame.contains("\"memoryKind\":\"core\""));
        assertTrue(frame.contains("\"memoryKind\":\"relevant\""));
        assertTrue(frame.contains("\"similarity\":0.82"));
    }

    @Test
    public void chatSummaryUsesDedicatedMemoryType() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        CapturingEmitter cap = new CapturingEmitter();
        channels.register("s1", cap);
        MemoryEvidenceEmitter emitter = new MemoryEvidenceEmitter(channels, true);

        emitter.emitChatSummaryEvidence("s1", "tenant:user:s1", "用户正在讨论 Evidence Map");

        String frame = cap.sent.get(0);
        assertTrue(frame.contains("\"memoryType\":\"chat_summary\""));
        assertTrue(frame.contains("\"kind\":\"rolling_summary\""));
        assertTrue(frame.contains("用户正在讨论 Evidence Map"));
    }

    @Test
    public void emitFailureDoesNotPropagate() {
        ApprovalChannelRegistry channels = new ApprovalChannelRegistry();
        ResponseBodyEmitter broken = new ResponseBodyEmitter() {
            @Override
            public void send(Object object) {
                throw new RuntimeException("client disconnected");
            }
        };
        channels.register("s1", broken);
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(channels, true);

        e.emitLongTermEvidence("s1", List.of("[skill:Java] x"));
        e.emitEpisodicEvidence("s1", "summary", List.of("other"));
        // 不抛即通过
    }

    @Test
    public void nullRegistryHandledGracefully() {
        MemoryEvidenceEmitter e = new MemoryEvidenceEmitter(null, true);
        e.emitLongTermEvidence("s1", List.of("[skill:Java] x"));
        // 不抛即通过
    }

    private static class CapturingEmitter extends ResponseBodyEmitter {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(Object object) {
            sent.add(String.valueOf(object));
        }
    }
}
