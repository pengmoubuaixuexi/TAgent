package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AutomaticArtifactManagerTest {
    private OpenAiChatOptions options(ToolCapabilityProfile profile) {
        return OpenAiChatOptions.builder().toolContext(Map.of("agent.run_id","run-a","userId","owner",
                "sessionId","session",ToolCapabilities.TOOL_CONTEXT_KEY,profile.name())).build();
    }
    private ChatResponse calls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(calls)).build())));
    }
    private AssistantMessage.ToolCall call(String id,String name,String args){return new AssistantMessage.ToolCall(id,"function",name,args);}
    private String text(ToolExecutionResult result){return ((ToolResponseMessage)result.conversationHistory().get(result.conversationHistory().size()-1)).getResponses().get(0).responseData();}
    @Test public void artifactDefinitionAndExecutionHonorEveryStageAndCannotBeShadowed() {
        for(var profile:ToolCapabilityProfile.values()) {
            var service=mock(RunArtifactService.class);
            when(service.definition()).thenReturn(ToolDefinition.builder().name("create_artifact").description("save").inputSchema("{}").build());
            when(service.create(anyString(),anyString(),anyMap())).thenReturn("{\"ok\":true}");
            var definition=service.definition();
            var shadow=mock(ToolCallback.class);when(shadow.getToolDefinition()).thenReturn(definition);
            var manager=new RobustToolCallingManager(ToolCallingManager.builder().build());manager.setRunArtifactService(service);
            var options=options(profile);options.setToolCallbacks(List.of(shadow));
            assertEquals(profile.allows(ToolCapability.CREATE_ARTIFACT)?1:0,
                    manager.resolveToolDefinitions(options).stream().filter(d->d.name().equals("create_artifact")).count());
            var result=manager.executeToolCalls(new Prompt("generate report",options),calls(call("call-a","create_artifact","{}")));
            assertEquals(profile.allows(ToolCapability.CREATE_ARTIFACT),text(result).contains("\"ok\":true"));
            verify(shadow,never()).call(anyString());
            if(profile.allows(ToolCapability.CREATE_ARTIFACT))verify(service).create(eq("call-a"),eq("{}"),eq(options.getToolContext()));
            else verify(service,never()).create(anyString(),anyString(),anyMap());
        }
    }
    @Test public void forcedNoToolsAndMissingImplementationFailClosed() {
        var service=mock(RunArtifactService.class);var manager=new RobustToolCallingManager(ToolCallingManager.builder().build());manager.setRunArtifactService(service);
        var options=options(ToolCapabilityProfile.ALL);var context=new HashMap<>(options.getToolContext());context.put(RobustToolCallingManager.FORCE_NO_TOOLS_KEY,true);options.setToolContext(context);
        assertTrue(manager.resolveToolDefinitions(options).isEmpty());
        manager.executeToolCalls(new Prompt("query",options),calls(call("a","create_artifact","{}")));verifyNoInteractions(service);
        manager.setRunArtifactService(null);assertTrue(text(manager.executeToolCalls(new Prompt("query",options(ToolCapabilityProfile.ALL)),calls(call("a","create_artifact","{}")))).contains("不能保存"));
    }
    @Test public void partialJsonDoesNotSearchOrExecuteSiblingAndRepairsOnlyWireHistory() {
        var catalog=mock(McpToolCatalogService.class);var delegate=mock(ToolCallingManager.class);
        var manager=new RobustToolCallingManager(delegate);manager.setMcpToolCatalogService(catalog);manager.setRequestToolEnabled(true);
        var original=calls(call("bad","request_tool","{\"needs\": "),call("write","remote_write","{}"));
        var result=manager.executeToolCalls(new Prompt("query",options(ToolCapabilityProfile.ALL)),original);
        assertTrue(text(result).contains("INVALID_TOOL_ARGUMENTS"));verifyNoInteractions(catalog,delegate);
        var assistant=(AssistantMessage)result.conversationHistory().get(result.conversationHistory().size()-2);
        assertEquals("{}",assistant.getToolCalls().get(0).arguments());assertEquals("bad",assistant.getToolCalls().get(0).id());
        assertEquals("{\"needs\": ",original.getResults().get(0).getOutput().getToolCalls().get(0).arguments());
        var responses=(ToolResponseMessage)result.conversationHistory().get(result.conversationHistory().size()-1);
        assertEquals(2,responses.getResponses().size());assertEquals("write",responses.getResponses().get(1).id());
    }
    @Test public void malformedAndWrongTypedNeedsNeverBecomeSearchQueries() {
        var catalog=mock(McpToolCatalogService.class);var manager=new RobustToolCallingManager(ToolCallingManager.builder().build());manager.setMcpToolCatalogService(catalog);manager.setRequestToolEnabled(true);
        for(String args:List.of("[]","null","{} {}","{\"needs\":[{}]}","{\"needs\":[]}","{\"needs\":5}","{\"unknown\":\"text\"}"))
            manager.executeToolCalls(new Prompt("query",options(ToolCapabilityProfile.ALL)),calls(call("a","request_tool",args)));
        verifyNoInteractions(catalog);
    }
    @Test public void providerFailureIsReadableWithoutExposingResponseBody() {
        var failure=org.springframework.web.reactive.function.client.WebClientResponseException.create(400,"bad",null,"secret-api-key".getBytes(),null);
        String message=StreamingRetryPolicy.failed("fixed",new IllegalStateException("Stream processing failed",failure)).getMessage();
        assertTrue(message.contains("HTTP 400"));assertFalse(message.contains("secret-api-key"));assertFalse(message.contains("Stream processing failed"));
    }

    private RunArtifactService artifactService() {
        var service=mock(RunArtifactService.class);
        when(service.definition()).thenReturn(ToolDefinition.builder().name("create_artifact").description("save").inputSchema("{}").build());
        return service;
    }

    @Test public void observedMalformedMarkdownGetsPreciseRepairThenStopsOnlyArtifactLoop() throws Exception {
        var service=artifactService();var manager=new RobustToolCallingManager(ToolCallingManager.builder().build());manager.setRunArtifactService(service);
        var opts=options(ToolCapabilityProfile.ARTIFACT_ONLY);
        String malformed="{\"title\":\"guide\",\"format\":\"MARKDOWN\",\"content\": # secret body";
        var first=manager.executeToolCalls(new Prompt("write",opts),calls(call("a","create_artifact",malformed)));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var error=json.readTree(text(first));
        assertTrue(error.path("retryable").asBoolean());assertTrue(error.path("column").asInt()>0);
        assertTrue(error.path("expected").asText().contains("markdown"));assertFalse(text(first).contains("secret body"));
        var copied=(OpenAiChatOptions)opts.copy();
        var second=manager.executeToolCalls(new Prompt(first.conversationHistory(),copied),calls(call("b","create_artifact",malformed)));
        assertFalse(json.readTree(text(second)).path("retryable").asBoolean());assertFalse(second.returnDirect());
        assertTrue(manager.resolveToolDefinitions(copied).isEmpty());
        var phantom=manager.executeToolCalls(new Prompt(second.conversationHistory(),copied),calls(call("c","create_artifact",malformed)));
        assertTrue(phantom.returnDirect());assertTrue(text(phantom).contains("未保存"));
        verify(service,never()).create(anyString(),anyString(),anyMap());
        assertEquals(1,manager.resolveToolDefinitions(options(ToolCapabilityProfile.ARTIFACT_ONLY)).size());
    }

    @Test public void validJsonSchemaFailuresShareRecoveryAndSuccessResetsIt() {
        var service=artifactService();when(service.create(anyString(),anyString(),anyMap()))
                .thenReturn("{\"ok\":false,\"message\":\"content.markdown 必须是字符串\"}","{\"ok\":true}",
                        "{\"ok\":false}","{\"ok\":false}");
        var manager=new RobustToolCallingManager(ToolCallingManager.builder().build());manager.setRunArtifactService(service);
        var opts=options(ToolCapabilityProfile.ARTIFACT_ONLY);
        for(int i=0;i<4;i++) {
            var result=manager.executeToolCalls(new Prompt("write",opts),calls(call("c"+i,"create_artifact","{}")));
            assertFalse(result.returnDirect());
            assertEquals(i<3?1:0,manager.resolveToolDefinitions(opts).size());
        }
        verify(service,times(4)).create(anyString(),anyString(),anyMap());
    }

    @Test public void unrelatedMalformedSiblingDoesNotConsumeArtifactRepairAndBatchCountsOnce() {
        var service=artifactService();var delegate=mock(ToolCallingManager.class);
        when(delegate.resolveToolDefinitions(any())).thenReturn(List.of());
        var manager=new RobustToolCallingManager(delegate);manager.setRunArtifactService(service);
        var opts=options(ToolCapabilityProfile.ALL);
        for(int i=0;i<3;i++)manager.executeToolCalls(new Prompt("write",opts),calls(call("bad","other","{"),call("ok","create_artifact","{}")));
        assertEquals(1,manager.resolveToolDefinitions(opts).size());
        manager.executeToolCalls(new Prompt("write",opts),calls(call("a","create_artifact","{"),call("b","create_artifact","{")));
        assertEquals(1,manager.resolveToolDefinitions(opts).size());
        verify(service,never()).create(anyString(),anyString(),anyMap());
    }

    @Test public void mixedSuccessDoesNotEraseFailedArtifactBatchAndBusinessToolsRemainAvailable() {
        var service=artifactService();when(service.create(anyString(),anyString(),anyMap()))
                .thenReturn("{\"ok\":false}","{\"ok\":true}","{\"ok\":false}","{\"ok\":true}");
        var callback=mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("search").description("search").inputSchema("{}").build());
        var manager=new RobustToolCallingManager(ToolCallingManager.builder().build());manager.setRunArtifactService(service);
        var opts=options(ToolCapabilityProfile.ALL);opts.setToolCallbacks(List.of(callback));
        for(int i=0;i<2;i++)manager.executeToolCalls(new Prompt("write",opts),calls(call("bad"+i,"create_artifact","{}"),call("ok"+i,"create_artifact","{}")));
        assertEquals(List.of("search"),manager.resolveToolDefinitions(opts).stream().map(ToolDefinition::name).toList());
    }
}
