package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.SessionRefCounter;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 第 61 轮：sessionId-scoped 全局引用计数器单测。
 */
public class SessionRefCounterTest {

    @Test
    public void firstAdvanceReturnsOne() {
        SessionRefCounter c = new SessionRefCounter();
        assertEquals(1, c.advance("s1", 3));
    }

    @Test
    public void secondAdvanceContinuesFromPrev() {
        SessionRefCounter c = new SessionRefCounter();
        c.advance("s1", 3);    // 占用 [1][2][3]
        assertEquals("第二批应从 4 起", 4, c.advance("s1", 2));  // 占用 [4][5]
        assertEquals("第三批应从 6 起", 6, c.advance("s1", 1));  // 占用 [6]
    }

    @Test
    public void sessionsIsolated() {
        SessionRefCounter c = new SessionRefCounter();
        c.advance("s1", 5);  // s1 用到 [5]
        assertEquals("s2 独立计数从 1 起", 1, c.advance("s2", 3));
        assertEquals("s1 继续从 6 起", 6, c.advance("s1", 1));
    }

    @Test
    public void nullSessionIdReturnsOne() {
        SessionRefCounter c = new SessionRefCounter();
        assertEquals(1, c.advance(null, 3));
        assertEquals(1, c.advance("  ", 3));
    }

    @Test
    public void zeroCountReturnsOne() {
        SessionRefCounter c = new SessionRefCounter();
        assertEquals(1, c.advance("s1", 0));
        assertEquals(1, c.advance("s1", -5));
    }

    @Test
    public void clearResetsCounter() {
        SessionRefCounter c = new SessionRefCounter();
        c.advance("s1", 5);
        c.clear("s1");
        assertEquals("clear 后应从 1 重新起", 1, c.advance("s1", 2));
    }

    @Test
    public void repeatedEvidenceReusesReferenceAcrossBatches() {
        SessionRefCounter c = new SessionRefCounter();
        assertEquals(List.of(1, 2, 3), c.resolveReferences("s1", List.of("a", "b", "c")));
        assertEquals(List.of(1, 2, 4), c.resolveReferences("s1", List.of("a", "b", "d")));
    }

    @Test
    public void stableReferencesShareSequenceWithLegacyAdvance() {
        SessionRefCounter c = new SessionRefCounter();
        assertEquals(List.of(1, 2), c.resolveReferences("s1", List.of("a", "b")));
        assertEquals(3, c.advance("s1", 2));
        assertEquals(List.of(2, 5), c.resolveReferences("s1", List.of("b", "c")));
    }
}
