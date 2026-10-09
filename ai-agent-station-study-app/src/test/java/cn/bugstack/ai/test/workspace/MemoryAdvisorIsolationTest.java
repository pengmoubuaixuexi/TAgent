package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.EpisodicMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryRecall;
import cn.bugstack.ai.domain.agent.service.prompt.ContextEnvelopeComposer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Identity and extraction ownership remain request-scoped across overlapping calls and worker threads. */
public class MemoryAdvisorIsolationTest {
    @Before @After public void clearIdentity() { MDC.clear(); }

    @Test
    public void twoUsersReadOnlyTheirOwnMemoriesWithoutMdc() {
        var ltm = mock(ILongTermMemoryService.class);
        var episodic = mock(IEpisodicMemoryService.class);
        var longTerm = new LongTermMemoryAdvisor(ltm, 4);
        var episodes = new EpisodicMemoryAdvisor(episodic, 3);
        for (String user : List.of("alice", "bob")) {
            when(ltm.retrieveForInjectionDetailed(eq(user), anyString(), eq(30), eq(4)))
                    .thenReturn(List.of(LongTermMemoryRecall.builder().topic("偏好:回答风格")
                            .content(user + " private preference").kind(LongTermMemoryRecall.KIND_CORE).build()));
            when(episodic.findBySessionIdForUser(user, "session:" + user)).thenReturn(user + " private summary");
            var request = request("tenant:" + user + ":session:" + user, false);
            var withLongTerm = longTerm.before(request, null);
            var withEpisodes = episodes.before(withLongTerm, null);
            assertTrue(withEpisodes.context().get(ContextEnvelopeComposer.CTX_LTM).toString().contains(user + " private"));
            assertTrue(withEpisodes.context().get(ContextEnvelopeComposer.CTX_EPISODIC).toString().contains(user + " private"));
            String other = user.equals("alice") ? "bob" : "alice";
            assertFalse(withEpisodes.context().toString().contains(other + " private"));
            verify(ltm).retrieveForInjectionDetailed(user, "Remember my preference", 30, 4);
            verify(episodic).findBySessionIdForUser(user, "session:" + user);
            verify(episodic).getOtherSessions(user, "session:" + user, 3, 5);
        }
        assertNull(MDC.get("userId"));
    }

    @Test
    public void missingOrConflictingIdentitiesNeverReadMemories() {
        var ltm = mock(ILongTermMemoryService.class);
        var episodic = mock(IEpisodicMemoryService.class);
        var longTerm = new LongTermMemoryAdvisor(ltm, 4);
        var episodes = new EpisodicMemoryAdvisor(episodic, 3);
        for (String conversation : List.of("raw-session", "alice:session", "tenant::session", "tenant:alice:", "")) {
            var request = request(conversation, true);
            assertSame(request, longTerm.before(request, null));
            assertSame(request, episodes.before(request, null));
        }
        MDC.put("userId", "bob");
        var conflict = request("tenant:alice:session", true);
        assertSame(conflict, longTerm.before(conflict, null));
        assertSame(conflict, episodes.before(conflict, null));
        MDC.clear();
        var contextConflict = ChatClientRequest.builder().prompt(new Prompt("Query"))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "tenant:alice:session", "userId", "bob")).build();
        assertSame(contextConflict, longTerm.before(contextConflict, null));
        assertSame(contextConflict, episodes.before(contextConflict, null));
        verifyNoInteractions(ltm, episodic);
    }

    @Test
    public void conversationSessionWinsOverUnrelatedWorkerThreadSession() {
        var episodic = mock(IEpisodicMemoryService.class);
        MDC.put("userId", "alice");
        MDC.put("sessionId", "previous-session");
        new EpisodicMemoryAdvisor(episodic, 3).before(request("tenant:alice:current-session", false), null);
        verify(episodic).findBySessionIdForUser("alice", "current-session");
        verify(episodic).getOtherSessions("alice", "current-session", 3, 5);
        verify(episodic, never()).findBySessionIdForUser(anyString(), eq("previous-session"));
    }

    @Test
    public void configuredRecallLimitsReachBothMemoryServices() {
        var ltm = mock(ILongTermMemoryService.class);
        var episodic = mock(IEpisodicMemoryService.class);
        var request = request("tenant:alice:session", false);
        new LongTermMemoryAdvisor(ltm, 11).before(request, null);
        new EpisodicMemoryAdvisor(episodic, 7).before(request, null);
        verify(ltm).retrieveForInjectionDetailed("alice", "Remember my preference", 30, 11);
        verify(episodic).getOtherSessions("alice", "session", 7, 5);
    }

    @Test
    public void overlappingRequestsExtractForTheirCapturedUsersAcrossThreadsExactlyOnce() throws Exception {
        var ltm = mock(ILongTermMemoryService.class);
        var extractor = mock(ChatClient.class);
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var call = mock(ChatClient.CallResponseSpec.class);
        Set<String> extractionIdentities = ConcurrentHashMap.newKeySet();
        when(extractor.prompt(any(Prompt.class))).thenAnswer(invocation -> {
            extractionIdentities.add(MDC.get("userId") + ":" + MDC.get("sessionId"));
            return spec;
        });
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("TOPIC: 偏好:回答风格 | CONTENT: 喜欢简明的回答。");
        CountDownLatch saved = new CountDownLatch(2);
        Set<String> savedOwners = ConcurrentHashMap.newKeySet();
        when(ltm.save(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            savedOwners.add(invocation.getArgument(0) + ":" + invocation.getArgument(4));
            saved.countDown();
            return "synthetic-memory-id";
        });
        var advisor = new LongTermMemoryAdvisor(ltm, 4, -100, extractor);
        // before(A), before(B), after(B), after(A) used to overwrite thread-local owner and text.
        var alice = advisor.before(request("tenant:alice:session-alice", true), null);
        var bob = advisor.before(request("tenant:bob:session-bob", true), null);
        var workers = Executors.newFixedThreadPool(2);
        try {
            workers.submit(() -> advisor.after(response(bob.context()), null)).get(3, TimeUnit.SECONDS);
            workers.submit(() -> advisor.after(response(alice.context()), null)).get(3, TimeUnit.SECONDS);
            assertTrue("Both isolated extractions should complete", saved.await(5, TimeUnit.SECONDS));
            advisor.after(response(alice.context()), null);
            advisor.after(response(bob.context()), null);
            assertEquals(Set.of("alice:session-alice", "bob:session-bob"), savedOwners);
            assertEquals(savedOwners, extractionIdentities);
            verify(ltm, times(2)).save(anyString(), anyString(), anyString(), anyString(), anyString());
            verify(extractor, times(2)).prompt(any(Prompt.class));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void mismatchedOrMissingResponseContextNeverWritesAnotherUsersMemories() {
        var ltm = mock(ILongTermMemoryService.class);
        var extractor = mock(ChatClient.class);
        var advisor = new LongTermMemoryAdvisor(ltm, 4, -100, extractor);
        var alice = advisor.before(request("tenant:alice:session-alice", true), null);
        MDC.put("userId", "bob");
        advisor.after(response(alice.context()), null);
        MDC.clear();
        advisor.after(response(Map.of()), null);
        advisor.after(response(Map.of(ChatMemory.CONVERSATION_ID, "tenant:bob:session-bob")), null);
        verifyNoInteractions(extractor);
        verify(ltm, never()).save(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void aReusedRequestContextDoesNotRetainAnEarlierPendingExtraction() {
        var ltm = mock(ILongTermMemoryService.class);
        var extractor = mock(ChatClient.class);
        var advisor = new LongTermMemoryAdvisor(ltm, 4, -100, extractor);
        var alice = advisor.before(request("tenant:alice:session-alice", true), null);
        Map<String, Object> reused = new java.util.LinkedHashMap<>(alice.context());
        reused.put(ChatMemory.CONVERSATION_ID, "tenant:bob:session-bob");
        reused.put(LongTermMemoryAdvisor.MEMORY_PERSIST_CONTEXT_KEY, false);
        var bob = advisor.before(ChatClientRequest.builder().prompt(new Prompt("Next request")).context(reused).build(), null);
        advisor.after(response(bob.context()), null);
        verifyNoInteractions(extractor);
        verify(ltm).retrieveForInjectionDetailed("bob", "Next request", 30, 4);
    }

    private ChatClientRequest request(String conversation, boolean persist) {
        return ChatClientRequest.builder().prompt(new Prompt("Remember my preference"))
                .context(Map.of(ChatMemory.CONVERSATION_ID, conversation,
                        LongTermMemoryAdvisor.MEMORY_PERSIST_CONTEXT_KEY, persist)).build();
    }

    private ChatClientResponse response(Map<String, Object> context) {
        return ChatClientResponse.builder().context(context).chatResponse(new ChatResponse(List.of(new Generation(
                new AssistantMessage("This is a sufficiently long assistant answer to enable the memory extraction path."))))).build();
    }
}
