package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.config.ReactorContextPropagationConfig;
import cn.bugstack.ai.domain.agent.model.valobj.RagRouterDecision;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;
import cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageContext;
import cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.IRerankService;
import cn.bugstack.ai.domain.agent.service.router.IRagRouter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real ChatClient/BaseAdvisor scheduler hops and parallel RAG workers, without external services. */
public class AdvisorRunBudgetPropagationTest {
    private final List<String> runs = new ArrayList<>();
    private final List<ModelUsageAccounting.Attempt> attempts = new CopyOnWriteArrayList<>();
    private ModelUsageAccounting ledger;

    @Before public void configure() {
        MDC.clear();
        new ReactorContextPropagationConfig().enableMdcContextPropagation();
        ledger = mock(ModelUsageAccounting.class);
        when(ledger.begin(any())).thenAnswer(invocation -> {
            attempts.add(invocation.getArgument(0));
            return mock(ModelUsageAccounting.Ticket.class);
        });
        ModelUsageLedger.install(ledger);
    }

    @After public void clean() {
        MDC.clear();
        runs.forEach(RunBudgetRegistry::finish);
        ModelUsageLedger.uninstall(ledger);
    }

    @Test public void streamAdvisorsAndParallelRetrievalChargeTheOriginalRunForEachUser() {
        ILongTermMemoryService memories = mock(ILongTermMemoryService.class);
        VectorStore vector = mock(VectorStore.class);
        BM25SearchService keyword = mock(BM25SearchService.class);
        IRagRouter router = mock(IRagRouter.class);
        IRerankService rerank = mock(IRerankService.class);
        List<String> threads = new CopyOnWriteArrayList<>();
        List<StreamingActivityTracker.Progress> progress = new CopyOnWriteArrayList<>();
        when(memories.retrieveForInjectionDetailed(anyString(), anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    assertEquals(invocation.getArgument(0), MDC.get("userId"));
                    assertEquals("memory_retrieval", progress.get(progress.size() - 1).phase());
                    charge("LTM_EMBEDDING", threads);
                    return List.of();
                });
        when(router.decide(anyString())).thenAnswer(invocation -> {
            assertEquals("rag_routing", progress.get(progress.size() - 1).phase());
            charge("RAG_ROUTER", threads);
            return RagRouterDecision.retrieve();
        });
        when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            String owner = HybridRetriever.extractEqualityFilters(invocation.<SearchRequest>getArgument(0).getFilterExpression()).get("user_id");
            assertEquals(owner, MDC.get("userId"));
            assertEquals("knowledge_retrieval", progress.get(progress.size() - 1).phase());
            charge("RAG_EMBEDDING", threads);
            return List.of(new Document("owned-evidence", "Owned evidence", Map.of("user_id", owner)));
        });
        when(keyword.search(anyString(), anyString(), anyMap(), anyInt())).thenReturn(List.of());
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> {
            assertEquals("knowledge_reranking", progress.get(progress.size() - 1).phase());
            charge("RERANK", threads);
            return invocation.getArgument(1);
        });
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.defer(() -> {
            assertEquals("Advisor stages must end before the main model begins", "preparing_model", progress.get(progress.size() - 1).phase());
            charge("MAIN", threads);
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
        }));
        HybridRetriever hybrid = new HybridRetriever();
        ReflectionTestUtils.setField(hybrid, "vectorStore", vector);
        ReflectionTestUtils.setField(hybrid, "bm25SearchService", keyword);
        try {
            ChatClient client = ChatClient.builder(model).defaultAdvisors(new LongTermMemoryAdvisor(memories, 4),
                    new RagAnswerAdvisor(vector, SearchRequest.builder().topK(2).build(), hybrid, rerank, 2, "kb", router)).build();
            for (String user : List.of("admin", "alice")) {
                var state = start(user);
                bind(state);
                progress.clear();
                var activity = StreamingActivityTracker.start("advisor-real-stream-" + user);
                activity.onProgress(progress::add);
                var stream = client.prompt().user("Find my evidence")
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "default:" + user + ":session"))
                        .stream().content();
                assertEquals(List.of("done"), StreamingActivityTracker.timeoutOnInactivity(stream, activity, Duration.ofSeconds(10))
                        .collectList().block(Duration.ofSeconds(10)));
                assertEquals(List.of("memory_retrieval", "rag_routing", "knowledge_retrieval", "knowledge_reranking"),
                        progress.stream().map(StreamingActivityTracker.Progress::phase).filter(phase -> !phase.equals("preparing_model")).toList());
                assertTrue(activity.isCancelled());
                assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(activity, "advisorWaits")).isEmpty());
                var billed = attempts.stream().filter(attempt -> attempt.runId().equals(state.runId())).toList();
                assertEquals(5, billed.size());
                assertTrue(billed.stream().allMatch(attempt -> user.equals(attempt.userId())));
                assertEquals(5, state.snapshot().get("modelCalls"));
                assertEquals(state.runId(), MDC.get("runId"));
                MDC.clear();
            }
            assertTrue(threads.stream().anyMatch(name -> name.startsWith("boundedElastic")));
            assertTrue(threads.stream().anyMatch(name -> name.startsWith("rag-retrieval-")));
            verify(ledger, times(2)).openRun(anyString(), anyString(), anyLong(), eq(false));
        } finally { hybrid.close(); }
    }

    @Test public void reusedWorkerRestoresRunAliasesAndPurposeWithoutLeakingIntoTheNextSubscription() {
        var worker = Schedulers.newSingle("advisor-budget-reuse");
        try {
            for (String owner : List.of("alice", "bob")) {
                var state = start(owner);
                bind(state);
                assertEquals(state.runId(), Mono.fromCallable(() -> {
                    assertEquals(owner, MDC.get("userId"));
                    assertEquals(state.runId(), MDC.get("agent.run_id"));
                    assertEquals("ADVISOR_FIXTURE", MDC.get("modelPurpose"));
                    return RunBudgetRegistry.find(Map.of()).runId();
                }).subscribeOn(worker).block(Duration.ofSeconds(5)));
                MDC.clear();
                assertTrue(Mono.fromCallable(() -> RunBudgetRegistry.find(Map.of()) == null
                        && MDC.get("agent.run_id") == null && MDC.get("userId") == null
                        && MDC.get("modelPurpose") == null).subscribeOn(worker).block(Duration.ofSeconds(5)));
            }
        } finally { worker.dispose(); }
    }

    @Test public void oldIdentityOnlyPropagationReproducesTheMissingBudgetFailure() {
        var state = start("admin");
        bind(state);
        var contexts = io.micrometer.context.ContextRegistry.getInstance();
        contexts.removeThreadLocalAccessor("runId");
        contexts.removeThreadLocalAccessor("agent.run_id");
        VectorStore vector = mock(VectorStore.class);
        when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            assertEquals("admin", MDC.get("userId"));
            assertNull("The old whitelist propagates the user but loses the run", RunBudgetRegistry.find(Map.of()));
            charge("RAG_EMBEDDING", new ArrayList<>());
            return List.of();
        });
        ChatClient client = ChatClient.builder(mock(ChatModel.class)).defaultAdvisors(
                new RagAnswerAdvisor(vector, SearchRequest.builder().topK(2).build())).build();
        try {
            var error = assertThrows(IllegalStateException.class, () -> client.prompt().user("Find evidence")
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "default:admin:session"))
                    .stream().content().blockLast(Duration.ofSeconds(5)));
            assertEquals("Stream processing failed", error.getMessage());
            assertTrue(error.getCause() instanceof SecurityException);
            assertEquals("模型调用缺少运行预算或提供商归属，未发送请求", error.getCause().getMessage());
            verify(vector).similaritySearch(any(SearchRequest.class));
            verify(ledger, never()).begin(any());
        } finally { new ReactorContextPropagationConfig().enableMdcContextPropagation(); }
    }

    @Test public void conflictingUserCannotChargeAPropagatedOrExplicitForeignRun() {
        var alice = start("alice");
        bind(alice);
        MDC.put("userId", "bob");
        assertThrows(SecurityException.class, () -> RunBudgetRegistry.find(Map.of()));
        assertSame(alice, RunBudgetRegistry.find(Map.of("agent.run_id", alice.runId(), "userId", "alice")));
        MDC.put("userId", "alice");
        assertThrows(SecurityException.class, () -> RunBudgetRegistry.find(Map.of("agent.run_id", alice.runId(), "userId", "bob")));
        verify(ledger, never()).begin(any());
    }

    @Test public void plainAdvisorParametersCannotMintOrRecoverAnUnboundBudget() {
        var alice = start("alice");
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.defer(() -> {
            charge("MAIN", new ArrayList<>());
            return Flux.empty();
        }));
        ChatClient client = ChatClient.create(model);
        assertThrows(SecurityException.class, () -> client.prompt().user("agent.run_id=" + alice.runId())
                .advisors(spec -> spec.param("agent.run_id", alice.runId()).param("userId", "alice"))
                .stream().content().blockLast(Duration.ofSeconds(5)));
        verify(ledger, never()).begin(any());
    }

    @Test public void cancelledRunRemainsCancelledAfterTheAdvisorThreadSwitch() {
        var state = start("alice");
        bind(state);
        state.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> Mono.fromRunnable(() ->
                charge("RERANK", new ArrayList<>())).subscribeOn(Schedulers.boundedElastic()).block(Duration.ofSeconds(5)));
        verify(ledger, never()).begin(any());
    }

    @Test public void auxiliaryFailureRestoresCallerIdentityAndDoesNotCreateAReplacementRun() {
        var state = start("admin");
        bind(state);
        VectorStore vector = mock(VectorStore.class);
        when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            charge("RAG_EMBEDDING", new ArrayList<>());
            throw new IllegalStateException("fixture retrieval failure");
        });
        ChatModel model = mock(ChatModel.class);
        ChatClient client = ChatClient.builder(model).defaultAdvisors(new RagAnswerAdvisor(vector,
                SearchRequest.builder().topK(2).build())).build();
        var activity = StreamingActivityTracker.start("advisor-failure");
        List<StreamingActivityTracker.Progress> progress = new CopyOnWriteArrayList<>();
        activity.onProgress(progress::add);
        var stream = client.prompt().user("Find evidence")
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "default:admin:session"))
                .stream().content();
        assertThrows(IllegalStateException.class, () -> StreamingActivityTracker.timeoutOnInactivity(stream, activity,
                Duration.ofSeconds(5)).blockLast(Duration.ofSeconds(5)));
        assertTrue(progress.stream().anyMatch(update -> update.phase().equals("knowledge_retrieval")));
        assertEquals("preparing_model", progress.get(progress.size() - 1).phase());
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(activity, "advisorWaits")).isEmpty());
        assertEquals(state.runId(), MDC.get("runId"));
        assertEquals("admin", MDC.get("userId"));
        assertEquals(1, attempts.size());
        verify(ledger).openRun(eq(state.runId()), eq("admin"), anyLong(), eq(false));
        verify(model, never()).stream(any(Prompt.class));
    }

    private RunBudgetRegistry.State start(String owner) {
        String id = "advisor-fixture-" + UUID.randomUUID();
        runs.add(id);
        return RunBudgetRegistry.start(id, owner, new RunBudgetRegistry.Limits(Duration.ofMinutes(1), 50, 10, 100000));
    }

    private void bind(RunBudgetRegistry.State state) {
        MDC.put("userId", state.ownerUserId());
        MDC.put("runId", state.runId());
        MDC.put("agent.run_id", state.runId());
        MDC.put("modelPurpose", "ADVISOR_FIXTURE");
    }

    private void charge(String purpose, List<String> threads) {
        threads.add(Thread.currentThread().getName());
        var state = RunBudgetRegistry.find(Map.of());
        ModelRequestGuard.account(state, ModelUsageContext.api("fixture-api", purpose, "https://fixture.invalid"),
                "fixture-model", 1, 0);
        assertNotNull(state);
        state.reserveModel(1, 0).settle(1L);
    }
}
