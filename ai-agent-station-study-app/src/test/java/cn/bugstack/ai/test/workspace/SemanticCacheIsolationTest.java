package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.rag.cache.ISemanticCacheService;
import cn.bugstack.ai.domain.agent.service.rag.cache.SemanticCacheAdvisor;
import cn.bugstack.ai.infrastructure.adapter.repository.cache.SemanticCacheService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No database, embeddings or HTTP requests: tests the full answer-cache isolation contract. */
public class SemanticCacheIsolationTest {
    private String run;
    private ISemanticCacheService cache;
    private SemanticCacheAdvisor advisor;
    private CallAdvisorChain chain;
    @Before public void setup() {
        run=UUID.randomUUID().toString(); new RunBudgetRegistry().start(run,"alice");
        MDC.put("runId",run); MDC.put("userId","alice"); MDC.put("agentId","agent-a");
        MDC.put("agentVersion","1"); MDC.put("intentCategory","qa");
        cache=mock(ISemanticCacheService.class); advisor=new SemanticCacheAdvisor(cache,0.95);
        chain=mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(ChatClientResponse.builder().context(Map.of()).build());
    }
    @After public void cleanup(){RunBudgetRegistry.finish(run);MDC.clear();}
    private ChatClientRequest request(String question) {
        return ChatClientRequest.builder().prompt(new Prompt(List.of(new SystemMessage("policy"),new UserMessage(question))))
                .context(Map.of(SemanticCacheAdvisor.STATELESS,true,SemanticCacheAdvisor.MODEL_ID,"model-a")).build();
    }
    private ISemanticCacheService.Scope capture(String question) {
        advisor.adviseCall(request(question),chain);
        var captor=org.mockito.ArgumentCaptor.forClass(ISemanticCacheService.Scope.class);
        verify(cache).lookup(eq(question),eq(0.95),eq(10),captor.capture());
        var scope=captor.getValue();clearInvocations(cache);return scope;
    }
    @Test public void trustedOwnerAndEveryIdentityDimensionPartitionAnswers() {
        var first=capture("hello");assertEquals("alice",first.ownerUserId());
        MDC.put("agentVersion","2");assertNotEquals(first,capture("hello"));
        MDC.put("agentVersion","1");MDC.put("agentId","agent-b");assertNotEquals(first,capture("hello"));
    }
    @Test public void spoofedOwnerCannotReadCacheOrInvokeDownstreamModel() {
        MDC.put("userId","bob");
        assertThrows(SecurityException.class,()->advisor.adviseCall(request("hello"),chain));
        verifyNoInteractions(cache,chain);
    }
    @Test public void completeInputFingerprintChangesWithQuestionAndSystemPrompt() {
        var first=capture("hello");assertNotEquals(first.inputHash(),capture("other").inputHash());
        var modified=ChatClientRequest.builder().prompt(new Prompt(List.of(new SystemMessage("different"),new UserMessage("hello"))))
                .context(request("hello").context()).build();
        advisor.adviseCall(modified,chain);
        var captor=org.mockito.ArgumentCaptor.forClass(ISemanticCacheService.Scope.class);
        verify(cache).lookup(eq("hello"),eq(0.95),eq(10),captor.capture());
        assertNotEquals(first.inputHash(),captor.getValue().inputHash());
    }
    @Test public void missingVersionAndDebugBypassBeforeEmbedding() {
        MDC.remove("agentVersion");advisor.adviseCall(request("hello"),chain);
        MDC.put("agentVersion","1");MDC.put("step","workspace_node_debug");advisor.adviseCall(request("hello"),chain);
        verifyNoInteractions(cache);verify(chain,times(2)).nextCall(any());
    }
    @Test public void dynamicAdvisorsAndHistoryBypass() {
        advisor.adviseCall(ChatClientRequest.builder().prompt(new Prompt("hello")).context(Map.of()).build(),chain);
        advisor.adviseCall(ChatClientRequest.builder().prompt(new Prompt(List.of(new UserMessage("before"),
                new AssistantMessage("secret"),new UserMessage("hello")))).context(request("hello").context()).build(),chain);
        verifyNoInteractions(cache);
        assertTrue(advisor.getOrder()>1000);
    }
    @Test public void legacyUnscopedMethodsCannotReadOrPopulateCache() {
        var jdbc=mock(JdbcTemplate.class);var embedding=mock(EmbeddingModel.class);
        var service=new SemanticCacheService(jdbc,embedding);
        assertNull(service.lookup("hello",0.95,10));service.store("hello","private answer","model");
        assertNull(service.lookup("hello",0.95,10,new ISemanticCacheService.Scope(null,"a","1","m","h")));
        verifyNoInteractions(jdbc,embedding);
    }
    @Test public void sqlIncludesEveryScopedPredicateAndJsonEscapesIdentity() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var embedding=mock(EmbeddingModel.class);
        when(embedding.embed("hello")).thenReturn(new float[]{1,0});
        var service=new SemanticCacheService(jdbc,embedding);
        var scope=new ISemanticCacheService.Scope("alice","agent-a","2","model\"quoted","hash");
        service.lookup("hello",0.95,10,scope);service.store("hello","private \"answer\"",scope);
        var invocations=mockingDetails(jdbc).getInvocations();
        var lookup=invocations.stream().filter(i->i.getMethod().getName().equals("queryForList")).findFirst().orElseThrow();
        String sql=(String)lookup.getArguments()[0];
        for(String key:List.of("owner_user_id","agent_id","agent_version","model_id","input_hash"))
            assertTrue(sql.contains("metadata->>'"+key+"' = ?"));
        var insert=invocations.stream().filter(i->i.getMethod().getName().equals("update")).findFirst().orElseThrow();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree((String)insert.getArguments()[2]);
        assertEquals("alice",json.get("owner_user_id").asText());assertEquals("model\"quoted",json.get("model_id").asText());
        assertEquals("private \"answer\"",json.get("response").asText());
    }
}
