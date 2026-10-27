package cn.bugstack.ai.test.router;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.router.RouteDecision;
import cn.bugstack.ai.domain.agent.service.router.UnifiedAgentRouter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A low-confidence route to the agent that made the referenced artifact is kept. */
public class RouterArtifactSourceTest {
    private final UnifiedAgentRouter router = new UnifiedAgentRouter();

    @Before
    public void setUp() {
        IAgentRepository repository = mock(IAgentRepository.class);
        when(repository.queryAiAgentByAgentId("8010")).thenReturn(agent("8010", "flowAgentExecuteStrategy"));
        when(repository.queryAiAgentByAgentId("8013")).thenReturn(agent("8013", "flowAgentExecuteStrategy"));
        IWorkspaceAccessRepository access = mock(IWorkspaceAccessRepository.class);
        when(access.ownsAgent(anyString(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(router, "repository", repository);
        ReflectionTestUtils.setField(router, "workspaceAccess", access);
        ReflectionTestUtils.setField(router, "lowConfidenceFallbackEnabled", true);
        ReflectionTestUtils.setField(router, "lowConfidenceFallbackThreshold", 0.9);
        ReflectionTestUtils.setField(router, "lowConfidenceFlowAgentId", "8013");
        MDC.put("userId", "10001");
    }

    @After
    public void tearDown() { MDC.remove("userId"); }

    @Test
    public void keepsTheArtifactSourceAgentBelowThreshold() {
        assertEquals("8010", normalize(Set.of("8010")).agentId());
    }

    @Test
    public void otherLowConfidenceRoutesStillFallBack() {
        assertEquals("8013", normalize(Set.of()).agentId());
        assertEquals("8013", normalize(Set.of("8009")).agentId());
    }

    private RouteDecision normalize(Set<String> sources) {
        return ReflectionTestUtils.invokeMethod(router, "normalizeRouteDecision",
                new RouteDecision("8010", List.of(), 0.86, "{}"), sources);
    }

    private static AiAgentVO agent(String id, String strategy) {
        AiAgentVO agent = new AiAgentVO();
        agent.setAgentId(id);
        agent.setStrategy(strategy);
        agent.setStatus(1);
        return agent;
    }
}
