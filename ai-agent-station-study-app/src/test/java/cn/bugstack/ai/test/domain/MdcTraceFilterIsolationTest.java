package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.config.MdcTraceFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.model.ModelInvocationScope;
import jakarta.servlet.ServletException;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** A pooled HTTP thread must not lend the prior chat's Run to a management operation. */
public class MdcTraceFilterIsolationTest {
    @After public void cleanMdc() { MDC.clear(); }

    @Test public void managementAfterACompletedChatGetsANewOwnedBudget() throws Exception {
        MdcTraceFilter filter = new MdcTraceFilter();
        String chat = "http-chat-" + UUID.randomUUID();
        filter.doFilter(request("alice"), new MockHttpServletResponse(), (req, res) -> {
            new RunBudgetRegistry().start(chat, "alice");
            MDC.put("runId", chat); MDC.put("agent.run_id", chat);
            MDC.put("sessionId", "old-session"); MDC.put("step", "old-step");
            RunBudgetRegistry.finish(chat);
        });
        assertNull(MDC.get("runId")); assertNull(MDC.get("sessionId"));
        AtomicReference<String> management = new AtomicReference<>();
        filter.doFilter(request("alice"), new MockHttpServletResponse(), (req, res) -> {
            try (var scope = ModelInvocationScope.open(new RunBudgetRegistry(), "alice", "MEMORY_MANAGEMENT")) {
                var budget = RunBudgetRegistry.find(Map.of());
                budget.checkActive();
                assertEquals("alice", budget.ownerUserId());
                assertNotEquals(chat, budget.runId());
                management.set(budget.runId());
            }
        });
        assertNotNull(management.get()); assertNull(MDC.get("runId"));
    }

    @Test public void staleDifferentUserRunIsClearedBeforeAuthenticatedRequest() throws Exception {
        String old = "stale-http-" + UUID.randomUUID();
        new RunBudgetRegistry().start(old, "alice"); RunBudgetRegistry.finish(old);
        MDC.put("runId", old); MDC.put("agent.run_id", old); MDC.put("userId", "alice");
        MDC.put("agentId", "old-agent"); MDC.put("sessionId", "old-session");
        MDC.put("step", "old-step"); MDC.put("modelPurpose", "CHAT_HELPER");
        MDC.put("traceId", "trace-kept"); MDC.put("spanId", "span-kept");
        new MdcTraceFilter().doFilter(request("bob"), new MockHttpServletResponse(), (req, res) -> {
            assertEquals("bob", MDC.get("userId"));
            assertNull(MDC.get("sessionId")); assertNull(MDC.get("modelPurpose"));
            try (var scope = ModelInvocationScope.open(new RunBudgetRegistry(), "bob", "MEMORY_MANAGEMENT")) {
                assertEquals("bob", ModelInvocationScope.currentUser());
                RunBudgetRegistry.find(Map.of()).checkActive();
            }
        });
        assertEquals("trace-kept", MDC.get("traceId")); assertEquals("span-kept", MDC.get("spanId"));
        assertNull(MDC.get("agentId")); assertNull(MDC.get("agent.run_id"));
    }

    @Test public void requestExceptionAlsoClearsRunFieldsAndPreservesTracing() throws Exception {
        MDC.put("traceId", "outer-trace");
        try {
            new MdcTraceFilter().doFilter(request("alice"), new MockHttpServletResponse(), (req, res) -> {
                MDC.put("runId", "failed-run"); MDC.put("agent.run_id", "failed-run");
                MDC.put("step", "failed-step"); MDC.put("modelPurpose", "FAILED_MODEL");
                throw new ServletException("synthetic failure");
            });
            fail("request must fail");
        } catch (ServletException expected) { assertEquals("synthetic failure", expected.getMessage()); }
        assertNull(MDC.get("runId")); assertNull(MDC.get("agent.run_id"));
        assertNull(MDC.get("step")); assertNull(MDC.get("modelPurpose"));
        assertNull(MDC.get("userId")); assertEquals("outer-trace", MDC.get("traceId"));
    }

    @Test public void leavingHttpThreadDoesNotFinishTheAsynchronousRun() throws Exception {
        String run = "async-http-" + UUID.randomUUID();
        AtomicReference<Map<String,String>> captured = new AtomicReference<>();
        new MdcTraceFilter().doFilter(request("alice"), new MockHttpServletResponse(), (req, res) -> {
            new RunBudgetRegistry().start(run, "alice");
            MDC.put("runId", run); MDC.put("agent.run_id", run);
            captured.set(MDC.getCopyOfContextMap());
        });
        assertNull(MDC.get("runId"));
        try {
            MDC.setContextMap(captured.get());
            assertEquals(run, RunBudgetRegistry.find(Map.of()).runId());
            RunBudgetRegistry.find(Map.of()).checkActive();
        } finally { RunBudgetRegistry.finish(run); }
    }

    private static MockHttpServletRequest request(String user) {
        var request = new MockHttpServletRequest("POST", "/api/v1/agent/memories");
        // In production AuthenticatedUserFilter supplies this server-owned header.
        request.addHeader(MdcTraceFilter.HEADER_USER_ID, user);
        return request;
    }
}
