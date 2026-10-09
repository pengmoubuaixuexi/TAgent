package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.model.valobj.RagRouterDecision;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;
import cn.bugstack.ai.domain.agent.service.rag.IParentDocumentService;
import cn.bugstack.ai.domain.agent.service.rag.fusion.RagFusionService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.HybridRetriever;
import cn.bugstack.ai.domain.agent.service.router.IRagRouter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real advisor/filter/fusion paths with in-memory search doubles; no models or databases. */
public class RagAnswerAdvisorIsolationTest {
    @Before @After public void clearIdentity() { MDC.clear(); }

    @Test
    public void missingIdentitySkipsRoutingAndEveryRetrievalEngine() {
        VectorStore vector = mock(VectorStore.class);
        HybridRetriever hybrid = mock(HybridRetriever.class);
        IRagRouter router = mock(IRagRouter.class);
        IParentDocumentService parents = mock(IParentDocumentService.class);
        var advisor = new RagAnswerAdvisor(vector, base(), hybrid, null, 2, "kb", router, null, null, null, null, parents);
        for (String conversation : List.of("raw-session", "tenant::session", "tenant:alice:", "")) {
            var request = request(Map.of(ChatMemory.CONVERSATION_ID, conversation));
            assertSame(request, advisor.before(request, null));
        }
        verifyNoInteractions(vector, hybrid, router, parents);
    }

    @Test
    public void namespacedConversationRestoresOwnerAfterMdcLossForTwoUsers() {
        VectorStore vector = mock(VectorStore.class);
        List<String> users = new ArrayList<>();
        when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            Map<String, String> filter = HybridRetriever.extractEqualityFilters(invocation.<SearchRequest>getArgument(0).getFilterExpression());
            assertEquals("kb", filter.get("knowledge"));
            users.add(filter.get("user_id"));
            return List.of(document(filter.get("user_id"), "Private evidence for " + filter.get("user_id")));
        });
        var advisor = new RagAnswerAdvisor(vector, base());
        var alice = advisor.before(request(Map.of(ChatMemory.CONVERSATION_ID, "tenant:alice:session-a")), null);
        var bob = advisor.before(request(Map.of(ChatMemory.CONVERSATION_ID, "tenant:bob:session-b")), null);
        assertEquals(List.of("alice", "bob"), users);
        assertTrue(alice.prompt().getContents().contains("Private evidence for alice"));
        assertFalse(alice.prompt().getContents().contains("Private evidence for bob"));
        assertTrue(bob.prompt().getContents().contains("Private evidence for bob"));
        assertNull("Recovered identity must not leak into reused worker threads", MDC.get("userId"));
    }

    @Test
    public void inconsistentIdentityFailsClosedAndPreservesExistingMdc() {
        VectorStore vector = mock(VectorStore.class);
        var advisor = new RagAnswerAdvisor(vector, base());
        MDC.put("userId", "bob");
        var request = request(Map.of(ChatMemory.CONVERSATION_ID, "tenant:alice:session-a"));
        assertSame(request, advisor.before(request, null));
        assertEquals("bob", MDC.get("userId"));
        MDC.clear();
        var explicitConflict = request(Map.of("userId", "alice", "user_id", "bob"));
        assertSame(explicitConflict, advisor.before(explicitConflict, null));
        verifyNoInteractions(vector);
    }

    @Test
    public void serverContextOrTrustedMdcCanSupplyAnOwnerWithoutCompoundConversation() {
        VectorStore vector = mock(VectorStore.class);
        when(vector.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var advisor = new RagAnswerAdvisor(vector, base());
        advisor.before(request(Map.of("userId", "alice")), null);
        MDC.put("userId", "bob");
        advisor.before(request(Map.of(ChatMemory.CONVERSATION_ID, "legacy-session")), null);
        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vector, times(2)).similaritySearch(captor.capture());
        assertEquals("alice", HybridRetriever.extractEqualityFilters(captor.getAllValues().get(0).getFilterExpression()).get("user_id"));
        assertEquals("bob", HybridRetriever.extractEqualityFilters(captor.getAllValues().get(1).getFilterExpression()).get("user_id"));
        assertEquals("bob", MDC.get("userId"));
    }

    @Test
    public void simpleDecompositionAndFusionCarrySameOwnerToVectorAndBm25Workers() {
        for (String path : List.of(RagRouterDecision.PATH_SIMPLE, RagRouterDecision.PATH_DECOMPOSE, RagRouterDecision.PATH_FUSION)) {
            VectorStore vector = mock(VectorStore.class);
            BM25SearchService bm25 = mock(BM25SearchService.class);
            HybridRetriever hybrid = new HybridRetriever();
            ReflectionTestUtils.setField(hybrid, "vectorStore", vector);
            ReflectionTestUtils.setField(hybrid, "bm25SearchService", bm25);
            List<Map<String, String>> vectorFilters = new CopyOnWriteArrayList<>();
            List<Map<String, String>> keywordFilters = new CopyOnWriteArrayList<>();
            when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
                vectorFilters.add(HybridRetriever.extractEqualityFilters(invocation.<SearchRequest>getArgument(0).getFilterExpression()));
                return List.of(document("alice", "owned vector evidence"));
            });
            when(bm25.search(anyString(), eq("kb"), anyMap(), anyInt())).thenAnswer(invocation -> {
                keywordFilters.add(Map.copyOf(invocation.<Map<String, String>>getArgument(2)));
                return List.of(document("alice", "owned keyword evidence"));
            });
            IRagRouter router = mock(IRagRouter.class);
            when(router.decide(anyString())).thenReturn(RagRouterDecision.builder().shouldRetrieve(true).path(path)
                    .subQueries(List.of("sub one", "sub two")).variants(List.of("variant one", "variant two")).build());
            var fusion = new RagFusionService(mock(ChatClient.class), vector, 3, true, hybrid, "kb");
            var advisor = new RagAnswerAdvisor(vector, base(), hybrid, null, 2, "kb", router, null, null, fusion);
            advisor.before(request(Map.of(ChatMemory.CONVERSATION_ID, "tenant:alice:session-a")), null);
            int queries = path.equals(RagRouterDecision.PATH_SIMPLE) ? 1 : path.equals(RagRouterDecision.PATH_DECOMPOSE) ? 2 : 3;
            assertEquals(path + " vector requests", queries, vectorFilters.size());
            assertEquals(path + " keyword requests", queries, keywordFilters.size());
            for (Map<String, String> filter : vectorFilters) assertEquals(Map.of("user_id", "alice", "knowledge", "kb"), filter);
            for (Map<String, String> filter : keywordFilters) assertEquals(Map.of("user_id", "alice", "knowledge", "kb"), filter);
            assertNull(MDC.get("userId"));
        }
    }

    @Test
    public void mismatchedParentCannotReplaceAnOwnedChild() {
        VectorStore vector = mock(VectorStore.class);
        when(vector.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document("alice", "owned child")));
        IParentDocumentService parents = mock(IParentDocumentService.class);
        when(parents.resolveParentDocuments(anyList())).thenReturn(List.of(document("bob", "OTHER_USER_PARENT_SECRET")));
        var advisor = new RagAnswerAdvisor(vector, base(), null, null, 2, "kb", null, null, null, null, null, parents);
        var response = advisor.before(request(Map.of(ChatMemory.CONVERSATION_ID, "tenant:alice:session-a")), null);
        assertTrue(response.prompt().getContents().contains("owned child"));
        assertFalse(response.prompt().getContents().contains("OTHER_USER_PARENT_SECRET"));
    }

    @Test
    public void recoveredMdcIsRemovedWhenTheVectorStoreFails() {
        VectorStore vector = mock(VectorStore.class);
        when(vector.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("fixture failure"));
        var advisor = new RagAnswerAdvisor(vector, base());
        assertThrows(IllegalStateException.class, () -> advisor.before(request(Map.of(ChatMemory.CONVERSATION_ID, "tenant:alice:session-a")), null));
        assertNull(MDC.get("userId"));
    }

    private SearchRequest base() { return SearchRequest.builder().topK(2).filterExpression("knowledge == 'kb'").build(); }
    private ChatClientRequest request(Map<String, Object> context) { return ChatClientRequest.builder().prompt(new Prompt("Find my evidence")).context(context).build(); }
    private Document document(String user, String text) { return new Document(user + ":" + text, text, Map.of("user_id", user, "knowledge", "kb")); }
}
