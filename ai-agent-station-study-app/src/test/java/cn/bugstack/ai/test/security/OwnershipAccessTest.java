package cn.bugstack.ai.test.security;

import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import cn.bugstack.ai.trigger.security.*;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.*;
import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnershipAccessTest {
    @After public void clear() { SecurityContextHolder.clearContext(); }
    @Test public void idempotencyResponsesAreNotSharedBetweenUsers() throws Exception {
        var filter = new cn.bugstack.ai.trigger.http.IdempotencyFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "ttlSeconds", 60);
        filter.init();
        try {
            for (String user : List.of("u1", "u2", "u1")) {
                var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/agent/feedback");
                request.addHeader("X-User-Id", user); request.addHeader("Idempotency-Key", "same-key");
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> res.getWriter().write(user));
                assertEquals(user, response.getContentAsString());
            }
        } finally { filter.destroy(); }
    }
    @Test public void evalVersionAndRunFollowDatasetOwner() {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        var access = new EvalAccess(dao);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("u1", null, List.of()));
        when(dao.findDataset("mine")).thenReturn(AiEvalDataset.builder().ownerUserId("u1").build());
        when(dao.findDataset("other")).thenReturn(AiEvalDataset.builder().ownerUserId("u2").build());
        when(dao.findVersion("v1")).thenReturn(AiEvalDatasetVersion.builder().datasetId("mine").build());
        when(dao.findVersion("v2")).thenReturn(AiEvalDatasetVersion.builder().datasetId("other").build());
        when(dao.findRun("r2")).thenReturn(AiEvalRun.builder().datasetId("other").build());
        access.dataset("mine"); access.version("v1");
        assertThrows(ResponseStatusException.class, () -> access.version("v2"));
        assertThrows(ResponseStatusException.class, () -> access.run("r2"));
        assertThrows(ResponseStatusException.class, () -> access.dataset("missing"));
    }
    @Test @SuppressWarnings("unchecked") public void sessionsAndRunsCannotCrossOwners() {
        IConversationOwnerDao owners = mock(IConversationOwnerDao.class);
        ObjectProvider<RunSnapshotService> provider = mock(ObjectProvider.class);
        RunSnapshotService runs = mock(RunSnapshotService.class);
        when(provider.getIfAvailable()).thenReturn(runs);
        when(owners.owner("s1")).thenReturn("u1");
        when(owners.owner("s2")).thenReturn("u2");
        var access = new ConversationAccess(owners, provider);
        access.session("s1", "u1", false);
        access.session("default:u1:s1", "u1", false);
        assertThrows(ResponseStatusException.class, () -> access.session("s2", "u1", true));
        assertThrows(ResponseStatusException.class, () -> access.session("default:u2:s2", "u1", false));
        when(runs.find("r2")).thenReturn(Optional.of(RunSnapshot.builder().userId("u2").build()));
        assertThrows(ResponseStatusException.class, () -> access.run("r2", "u1"));
        verify(owners, never()).claim("s2", "u1");
        when(runs.find("fresh")).thenReturn(Optional.empty());
        when(owners.owner("run:fresh")).thenReturn("u1");
        access.run("fresh", "u1", true);
        assertThrows(ResponseStatusException.class, () -> access.run("fresh", "u2", true));
        assertThrows(ResponseStatusException.class, () -> access.run("fresh", "u1", false));
    }
}
