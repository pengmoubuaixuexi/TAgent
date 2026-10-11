package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import cn.bugstack.ai.domain.agent.service.IArmoryService;
import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.router.*;
import cn.bugstack.ai.domain.agent.service.workspace.WorkspaceExecutionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.ApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Calls the real isolated-debug assembly; the model invocation alone is an offline stub. */
public class WorkspaceNodeDebugTest {
    final ObjectMapper json=new ObjectMapper();
    WorkspaceService workspace; IArmoryService armory; IAgentRepository repository; ApplicationContext beans;
    McpToolCatalogService catalog;
    WorkspaceNodeDebugService service; Map<String,Object> scope; AtomicInteger invocations=new AtomicInteger();
    boolean fail;
    @Before public void setup() {
        workspace=mock(WorkspaceService.class);armory=mock(IArmoryService.class);repository=mock(IAgentRepository.class);beans=mock(ApplicationContext.class);
        when(workspace.agent("alice","agent-a")).thenReturn(Map.of("editable",true,"version",2,"status",1,
                "nodes",List.of(Map.of("nodeId","plan","role","PLANNING_CLIENT","name","规划"),Map.of("nodeId","execute","role","EXECUTOR_CLIENT","name","执行"))));
        when(workspace.agent("bob","agent-a")).thenThrow(WorkspaceService.notFound());
        when(repository.queryAiAgentClientsByAgentId("agent-a")).thenReturn(List.of(
                AiAgentClientFlowConfigVO.builder().clientId("client-execute").sequence(2).build(),
                AiAgentClientFlowConfigVO.builder().clientId("client-plan").sequence(1).build()));
        when(repository.queryWorkspaceNodePolicy(anyString())).thenAnswer(call->new WorkspaceNodePolicy("agent-a",call.getArgument(0),"task instructions",Map.of(),Set.of()));
        when(repository.AiClientVOByClientIds(anyList())).thenAnswer(call->List.of(AiClientVO.builder()
                .clientId(((List<String>)call.getArgument(0)).get(0)).modelId("m").promptIdList(List.of("p")).advisorIdList(List.of()).build()));
        when(repository.queryAiClientSystemPromptMapByClientIds(anyList())).thenReturn(Map.of());
        OpenAiChatModel model=OpenAiChatModel.builder().openAiApi(OpenAiApi.builder().baseUrl("http://127.0.0.1:1").apiKey("not-a-real-key").build()).build();
        when(beans.getBean(anyString(),eq(OpenAiChatModel.class))).thenReturn(model);
        catalog=mock(McpToolCatalogService.class);
        service=new WorkspaceNodeDebugService(workspace,armory,repository,beans,new AgentToolRegistry(),catalog,
                new ToolResultReferenceService((cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService)null),new ToolCallLedger(),
                new WorkspaceExecutionGuard(),mock(LlmObservationRecorder.class),json) {
            @Override protected ChatClientResponse invoke(ChatClient client,String input,Map<String,Object> context,String conversationId) {
                invocations.incrementAndGet();scope=context;
                assertEquals("default:alice:"+context.get("sessionId"),conversationId);
                assertEquals("alice",MDC.get("userId"));
                RunBudgetRegistry.State budget=RunBudgetRegistry.find(context);assertNotNull(budget);budget.reserveTool();
                if(fail)throw new IllegalStateException("provider key must never reach browser");
                return ChatClientResponse.builder().chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"steps\":[\"free planning output\"]}"))))).build();
            }
        };
    }
    @After public void clear() {MDC.clear();}
    @Test public void rejectsNonOwnerStaleVersionAndForeignNodeBeforeAssembly() throws Exception {
        for(Runnable request:List.<Runnable>of(
                ()->service.debug("bob","agent-a","plan",json.createObjectNode().put("version",2).put("input","x")),
                ()->service.debug("alice","agent-a","plan",json.createObjectNode().put("version",1).put("input","x")),
                ()->service.debug("alice","agent-a","missing",json.createObjectNode().put("version",2).put("input","x")))) {
            try {request.run();fail("must deny");}catch(ResponseStatusException expected){}
        }
        verifyNoInteractions(armory);assertEquals(0,invocations.get());
    }
    @Test public void planningUsesIndependentRunAndReturnsOutputWithoutParsingPlanOrExecutingOtherNodes() {
        MDC.put("runId","parent-run");MDC.put("userId","previous-user");
        var input=json.createObjectNode().put("version",2).put("input","测试规划").put("upstream","上游结果").put("allowReadOnlyTools",true);
        Map<String,Object> first=service.debug("alice","agent-a","plan",input);
        assertEquals("COMPLETED",first.get("status"));assertTrue(first.get("output").toString().contains("free planning output"));
        assertEquals("NONE",scope.get(ToolCapabilities.TOOL_CONTEXT_KEY));assertEquals("client-plan",scope.get("clientId"));
        assertEquals("parent-run",MDC.get("runId"));assertEquals("previous-user",MDC.get("userId"));
        Map<String,Object> second=service.debug("alice","agent-a","plan",input);
        assertNotEquals(first.get("runId"),second.get("runId"));assertEquals(2,invocations.get());
        assertEquals(1,((Number)((Map<?,?>)first.get("usage")).get("toolAttempts")).intValue());
    }
    @Test public void executionRequiresExplicitToolOptInAndDoesNotRetryWholeNodeOnFailure() {
        var input=json.createObjectNode().put("version",2).put("input","测试执行");
        service.debug("alice","agent-a","execute",input);assertEquals("NONE",scope.get(ToolCapabilities.TOOL_CONTEXT_KEY));
        fail=true;input.put("allowReadOnlyTools",true);
        Map<String,Object> result=service.debug("alice","agent-a","execute",input);
        assertEquals("ALL",scope.get(ToolCapabilities.TOOL_CONTEXT_KEY));assertEquals(Boolean.TRUE,scope.get(MeteredToolCallback.DEBUG_READ_ONLY_KEY));
        assertEquals("FAILED",result.get("status"));assertFalse(result.get("error").toString().contains("provider key"));assertEquals(2,invocations.get());
        verify(catalog,times(2)).cleanupRun(anyString());
    }
    @Test public void capturesRenderedPromptsAndOnlySafeKnowledgeSourceFields() {
        var capture=new WorkspaceNodeDebugService.DebugCapture();
        var document=new org.springframework.ai.document.Document("evidence text",Map.of("source","paper.pdf","knowledge","mine","authorization","secret"));
        var prompt=new org.springframework.ai.chat.prompt.Prompt(List.of(
                new org.springframework.ai.chat.messages.SystemMessage("rendered policy"),
                new org.springframework.ai.chat.messages.UserMessage("input with retrieved evidence")));
        capture.before(ChatClientRequest.builder().prompt(prompt).context(Map.of("qa_retrieved_documents",List.of(document),"apiKey","not-for-ui")).build(),null);
        assertEquals("rendered policy",capture.system);assertEquals("input with retrieved evidence",capture.user);
        assertEquals(List.of(Map.of("excerpt","evidence text","source","paper.pdf","knowledge","mine")),capture.sources);
    }
}
