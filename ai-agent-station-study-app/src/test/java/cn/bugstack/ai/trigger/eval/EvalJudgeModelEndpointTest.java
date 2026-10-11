package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalDataset;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalJudgeJob;
import cn.bugstack.ai.trigger.http.EvalOpsController;
import cn.bugstack.ai.trigger.security.EvalAccess;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real controller route and dataset ownership interceptor without a model call. */
public class EvalJudgeModelEndpointTest {
    private EvalJudgeService judge;
    private MockMvc mvc;

    @Before public void setup() {
        var dao = mock(IAiEvalOpsDao.class);
        when(dao.findRun("isolated-run")).thenReturn(AiEvalRun.builder()
                .evalRunId("isolated-run").datasetId("dataset-1").userId("eval-isolated").build());
        when(dao.findDataset("dataset-1")).thenReturn(AiEvalDataset.builder()
                .datasetId("dataset-1").ownerUserId("alice").build());
        when(dao.findJudgeJob("job-1")).thenReturn(AiEvalJudgeJob.builder()
                .judgeJobId("job-1").evalRunId("isolated-run").build());
        judge = mock(EvalJudgeService.class);
        when(judge.listJudgeModels("isolated-run")).thenReturn(List.of(Map.of(
                "modelName", "deepseek-fixture", "apiId", "own-api", "apiName", "provider.test", "source", "OWNED")));
        var controller = new EvalOpsController(mock(EvalDatasetService.class), mock(EvalRunService.class),
                judge, mock(EvalCodeVersionService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new EvalAccess(dao)).build();
    }

    private void login(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @After public void cleanup() { SecurityContextHolder.clearContext(); }

    @Test public void ownerCanLoadModelsForAnIsolatedRunWithoutSupplyingItsSyntheticUser() throws Exception {
        login("alice", "USER");
        mvc.perform(get("/api/v1/eval/runs/isolated-run/judge-models").accept("application/json")
                        .param("userId", "bob").header("X-User-Id", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data[0].modelName").value("deepseek-fixture"))
                .andExpect(jsonPath("$.data[0].apiName").value("provider.test"))
                .andExpect(jsonPath("$.data[0].source").value("OWNED"));
        verify(judge).listJudgeModels("isolated-run");
    }

    @Test public void anotherOwnerCannotReadCandidateModelsEvenWithAnAdminRole() throws Exception {
        for (String role : List.of("USER", "ADMIN")) {
            login("bob", role);
            mvc.perform(get("/api/v1/eval/runs/isolated-run/judge-models").param("userId", "alice"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(judge);
    }

    @Test public void ownerCanRetryJudgeFailures() throws Exception {
        login("alice", "USER");
        when(judge.retryFailed("job-1")).thenReturn(Map.of("judgeJobId", "retry-1"));
        mvc.perform(post("/api/v1/eval/judge-jobs/job-1/retry-failed").accept("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.judgeJobId").value("retry-1"));
        verify(judge).retryFailed("job-1");
    }

    @Test public void anotherOwnerCannotRetryEvenWithAdminRoleOrForgedOwnerParameter() throws Exception {
        for (String role : List.of("USER", "ADMIN")) {
            login("bob", role);
            mvc.perform(post("/api/v1/eval/judge-jobs/job-1/retry-failed").param("userId", "alice"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(judge);
    }
}
