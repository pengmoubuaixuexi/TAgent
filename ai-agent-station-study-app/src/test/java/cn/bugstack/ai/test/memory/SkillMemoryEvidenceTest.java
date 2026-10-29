package cn.bugstack.ai.test.memory;

import cn.bugstack.ai.domain.agent.service.memory.longterm.SkillMemoryEvidence;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SkillMemoryEvidenceTest {
    @Before @After public void clearIdentity() { org.slf4j.MDC.clear(); }
    @Test public void questionsAndExpertCorrectionsDoNotProveMastery() {
        assertNull(SkillMemoryEvidence.verifiedContent("请解释volatile", "请解释volatile"));
        assertNull(SkillMemoryEvidence.verifiedContent("请按JLS核对final字段示例", "请按JLS核对final字段示例"));
        assertNull(SkillMemoryEvidence.verifiedContent("我是否掌握Java？", "我是否掌握Java？"));
        assertNull(SkillMemoryEvidence.verifiedContent("Do I know Java?", "Do I know Java?"));
    }
    @Test public void assistantOrInventedAssertionsCannotBecomeUserSkills() {
        assertNull(SkillMemoryEvidence.verifiedContent("帮我学Java", "我熟悉Java"));
        assertNull(SkillMemoryEvidence.verifiedContent("我熟悉Java", null));
        assertNull(SkillMemoryEvidence.verifiedContent("我熟悉Java", "我精通Java并发"));
    }
    @Test public void negativeFutureAndHypotheticalClaimsAreNotMastery() {
        for (String text : new String[]{"我不会Java", "我还没掌握Java", "我想掌握Java", "假设我掌握Java",
                "I do not know Java", "I want to know Java"}) {
            assertNull(text, SkillMemoryEvidence.verifiedContent(text, text));
        }
    }
    @Test public void explicitAbilityKeepsExactProficiencyWithoutModelInflation() {
        String user = "我熟悉Java，但还没学Python";
        assertEquals("我熟悉Java", SkillMemoryEvidence.verifiedContent(user, "我熟悉Java"));
        assertEquals("I am familiar with Java", SkillMemoryEvidence.verifiedContent("I am familiar with Java", "I am familiar with Java"));
        assertEquals("我的技术栈包括Java和SQL", SkillMemoryEvidence.verifiedContent("我的技术栈包括Java和SQL", "我的技术栈包括Java和SQL"));
        assertTrue(SkillMemoryEvidence.isSkillTopic("技能:Java"));
        assertTrue(SkillMemoryEvidence.isSkillTopic("skill:Java"));
        assertFalse(SkillMemoryEvidence.isSkillTopic("计划:Java学习"));
    }
    @Test public void automaticExtractionActuallyEnforcesEvidenceBeforePersisting() throws Exception {
        var ltm = mock(ILongTermMemoryService.class);
        var extractor = mock(ChatClient.class);
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var call = mock(ChatClient.CallResponseSpec.class);
        when(extractor.prompt(any(Prompt.class))).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("""
                TOPIC: 技能:JMM | CONTENT: 用户精通并发
                TOPIC: 技能:Java | CONTENT: 用户精通Java并发 | EVIDENCE: 我熟悉Java
                TOPIC: 技能:Python | CONTENT: 用户会Python | EVIDENCE: 我不会Python
                TOPIC: 偏好:回答风格 | CONTENT: 用户喜欢简明回答
                """);
        CountDownLatch saved = new CountDownLatch(2);
        when(ltm.save(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            saved.countDown(); return "saved";
        });
        LongTermMemoryAdvisor.triggerExtractionAsync(ltm, extractor,
                "我熟悉Java。我不会Python。请解释JMM，我喜欢简明回答。",
                "这是助手对JMM的详细解释。即使助手称赞用户精通并发，该称赞也不是用户自述。",
                "demo", "default", "session", "agent");
        assertTrue(saved.await(5, TimeUnit.SECONDS));
        verify(ltm).save("demo", "我熟悉Java", "技能:Java", "auto", "session");
        verify(ltm).save("demo", "用户喜欢简明回答", "偏好:回答风格", "auto", "session");
        verify(ltm, times(2)).save(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
