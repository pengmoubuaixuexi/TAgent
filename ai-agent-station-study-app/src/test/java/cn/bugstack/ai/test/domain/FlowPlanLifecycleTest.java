package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.api.dto.FlowPlanReviewConfirmRequestDTO;
import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentVO;
import cn.bugstack.ai.domain.agent.service.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.dispatch.AgentDispatchDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.event.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.plan.*;
import cn.bugstack.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.bugstack.ai.trigger.http.AiAgentController;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual controller + review service + dispatcher handoff, with no database or model requests. */
public class FlowPlanLifecycleTest {
    private static final String SESSION="flow-review-session";
    private static final List<FlowPlanReviewStep> STEPS=List.of(FlowPlanReviewStep.builder().stepNo(1).title("step").content("do the work").dependsOn(List.of()).build());
    private static final class States implements FlowPlanReviewStateStore {
        final Map<String,FlowPlanReviewState> rows=new ConcurrentHashMap<>();
        public void save(FlowPlanReviewState state,Duration ttl){rows.put(state.getRunId(),state);}
        public Optional<FlowPlanReviewState> find(String run){return Optional.ofNullable(rows.get(run));}
        public void update(FlowPlanReviewState state){rows.put(state.getRunId(),state);}
        public void delete(String run){rows.remove(run);}
    }
    private FlowPlanReviewService review(States states){
        var service=new FlowPlanReviewService();ReflectionTestUtils.setField(service,"stateStore",states);
        ReflectionTestUtils.setField(service,"enabled",true);ReflectionTestUtils.setField(service,"ttlSeconds",7200L);ReflectionTestUtils.setField(service,"maxSteps",50);return service;
    }
    private AiAgentController controller(FlowPlanReviewService review,RunEventPublisher publisher){
        var controller=new AiAgentController();ReflectionTestUtils.setField(controller,"flowPlanReviewService",review);
        ReflectionTestUtils.setField(controller,"runEventPublisher",publisher);return controller;
    }
    private FlowPlanReviewConfirmRequestDTO confirm(String run){return FlowPlanReviewConfirmRequestDTO.builder().runId(run).sessionId(SESSION)
            .steps(List.of(FlowPlanReviewConfirmRequestDTO.Step.builder().stepNo(1).title("step").content("do the work").dependsOn(List.of()).build())).build();}

    @Test public void duplicateOrInvalidConfirmationDoesNotFinishExistingRunOrDetachItsSubscriber(){
        for(String status:List.of("RUNNING","PENDING")) {
            String run=UUID.randomUUID().toString();var states=new States();var service=review(states);var publisher=new RunEventPublisher();
            var state=FlowPlanReviewState.builder().runId(run).sessionId(SESSION).status(status).initialPhaseReleased(true).expiresAt(System.currentTimeMillis()+60000).attemptCount(1).build();
            states.save(state,Duration.ofMinutes(1));
            var budget=RunBudgetRegistry.start(run,"alice",new RunBudgetRegistry.Limits(Duration.ofSeconds(30),10,10,10000));
            ResponseBodyEmitter existing=mock(ResponseBodyEmitter.class);publisher.attach(run,SESSION,existing,null);
            var request=confirm(run);if("PENDING".equals(status))request.setSteps(List.of());
            var rejected=controller(service,publisher).confirmFlowPlanReview(request,new MockHttpServletResponse());
            assertFalse(rejected instanceof RunAwareResponseBodyEmitter);budget.checkActive();
            assertEquals(run,publisher.currentRunId(SESSION));verify(existing,never()).complete();
            assertEquals(status,state.getStatus());publisher.finishRun(run);
        }
    }

    @Test public void confirmationWaitsForDispatcherCleanupAndThenPreservesResumedBudget() throws Exception {
        String run=UUID.randomUUID().toString();var states=new States();var review=review(states);var publisher=new RunEventPublisher();
        var dispatcher=new AgentDispatchDispatchService();var access=mock(IWorkspaceAccessRepository.class);var repository=mock(IAgentRepository.class);
        when(access.ownsAgent("alice","agent")).thenReturn(true);when(access.activeAgentVersion("alice","agent")).thenReturn(1);
        when(repository.queryAiAgentByAgentId("agent")).thenReturn(AiAgentVO.builder().agentId("agent").agentName("Flow").strategy("flow").build());
        var history=mock(IRunHistoryRepository.class);var strategy=mock(IExecuteStrategy.class);
        var pool=(ThreadPoolExecutor)Executors.newFixedThreadPool(1);var paused=new CountDownLatch(1);var release=new CountDownLatch(1);
        ReflectionTestUtils.setField(dispatcher,"workspaceAccess",access);ReflectionTestUtils.setField(dispatcher,"repository",repository);
        ReflectionTestUtils.setField(dispatcher,"executeStrategyMap",Map.of("flow",strategy));ReflectionTestUtils.setField(dispatcher,"threadPoolExecutor",pool);
        ReflectionTestUtils.setField(dispatcher,"runEventPublisher",publisher);ReflectionTestUtils.setField(dispatcher,"runBudgets",new RunBudgetRegistry());
        ReflectionTestUtils.setField(dispatcher,"runHistory",history);ReflectionTestUtils.setField(dispatcher,"flowPlanReviewService",review);
        doAnswer(call->{
            ExecuteCommandEntity request=call.getArgument(0);var context=new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
            context.setValue("emitter",call.getArgument(1));
            assertTrue(review.tryPauseForReview(request,context,Map.of("第1步","do the work"),Map.of(1,Set.of())));
            paused.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;
        }).when(strategy).execute(any(),any());
        var command=ExecuteCommandEntity.builder().userId("alice").aiAgentId("agent").sessionId(SESSION).runId(run).message("plan").build();
        ResponseBodyEmitter oldSubscriber=mock(ResponseBodyEmitter.class);publisher.attach(run,SESSION,oldSubscriber,null);
        MDC.put("runId","caller-before");MDC.put("agent.run_id","caller-before");
        try {
            pool.submit(()->MDC.put("runId","worker-before")).get(3,TimeUnit.SECONDS);
            dispatcher.dispatch(command,new RunAwareResponseBodyEmitter(Long.MAX_VALUE,run,SESSION,publisher));
            assertEquals("caller-before",MDC.get("runId"));assertTrue(paused.await(3,TimeUnit.SECONDS));
            assertEquals("plan_finalizing",review.prepareApprovedPlan(run,SESSION,STEPS).getErrorCode());
            var rejected=controller(review,publisher).confirmFlowPlanReview(confirm(run),new MockHttpServletResponse());
            assertFalse(rejected instanceof RunAwareResponseBodyEmitter);assertEquals(run,publisher.currentRunId(SESSION));verify(oldSubscriber,never()).complete();
            var budget=RunBudgetRegistry.find(run);assertTrue(budget.waitingForReview());budget.checkActive();
            release.countDown();assertEquals("worker-before",pool.submit(()->MDC.get("runId")).get(3,TimeUnit.SECONDS));
            verify(history).status(run,"BLOCKED",null);verify(history,never()).status(run,"COMPLETED",null);
            assertNull(dispatcher.activeRunId(SESSION));assertNull(publisher.currentRunId(SESSION));
            assertTrue(states.find(run).orElseThrow().getInitialPhaseReleased());
            assertTrue(review.prepareApprovedPlan(run,SESSION,STEPS).isReady());budget.resumeReview();
            ResponseBodyEmitter resumed=mock(ResponseBodyEmitter.class);publisher.attach(run,SESSION,resumed,null);budget.checkActive();
            controller(review,publisher).confirmFlowPlanReview(confirm(run),new MockHttpServletResponse());
            budget.checkActive();assertEquals(run,publisher.currentRunId(SESSION));verify(resumed,never()).complete();
        } finally {release.countDown();pool.shutdownNow();publisher.finishRun(run);MDC.remove("runId");MDC.remove("agent.run_id");}
    }

    @Test public void preparationExceptionCannotCloseAnUnownedRun(){
        String run=UUID.randomUUID().toString();var service=mock(FlowPlanReviewService.class);var publisher=new RunEventPublisher();
        var budget=RunBudgetRegistry.start(run,"alice",new RunBudgetRegistry.Limits(Duration.ofSeconds(30),2,2,100));
        ResponseBodyEmitter existing=mock(ResponseBodyEmitter.class);publisher.attach(run,SESSION,existing,null);
        when(service.prepareApprovedPlan(eq(run),eq(SESSION),anyList())).thenThrow(new IllegalStateException("fixture read unavailable"));
        controller(service,publisher).confirmFlowPlanReview(confirm(run),new MockHttpServletResponse());
        budget.checkActive();assertEquals(run,publisher.currentRunId(SESSION));verify(existing,never()).complete();publisher.finishRun(run);
    }
}
