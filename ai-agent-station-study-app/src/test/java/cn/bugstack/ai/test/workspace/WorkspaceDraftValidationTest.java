package cn.bugstack.ai.trigger.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceDraftValidationTest {
    @Test public void llmOutputCannotChooseOwnerToolsSecretsOrPublishedState() throws Exception {
        var service=new WorkspaceDraftService(null,null,new ObjectMapper(),null);
        var result=service.normalize("""
                {"name":"研究助手","description":"整理资料","nodes":[{"role":"DEFAULT","name":"回答","systemPrompt":"根据资料回答问题，不编造来源。",
                "modelId":"admin-private","mcpBindings":[{"mcpId":"secret"}],"advisors":[{"type":"SemanticCache"}]}],
                "modelId":"admin-private","strategy":"evil","owner_user_id":"admin","is_public":1,
                "mcpIds":["secret-mcp"],"bearerToken":"secret","advisors":[{"type":"SemanticCache"}]}
                ""","offered-model","fixed");
        assertEquals("offered-model",result.path("nodes").get(0).path("modelId").asText());
        assertEquals("fixed",result.path("strategy").asText());
        assertFalse(result.has("owner_user_id"));assertFalse(result.has("is_public"));assertFalse(result.has("bearerToken"));
        assertEquals(0,result.path("nodes").get(0).path("mcpBindings").size());
        assertEquals(0,result.path("nodes").get(0).path("advisors").size());
    }
    @Test public void invalidDraftIsRejectedWithoutSavingAnything() {
        var service=new WorkspaceDraftService(null,null,new ObjectMapper(),null);
        assertThrows(Exception.class,()->service.normalize("not json","model","fixed"));
        assertThrows(Exception.class,()->service.normalize("{\"name\":\"hi\"}","model","fixed"));
    }
    @Test public void fixedDraftCanContainSeveralDistinctOrderedSteps() throws Exception {
        var service=new WorkspaceDraftService(null,null,new ObjectMapper(),null);
        var result=service.normalize("""
                {"name":"翻译润色","nodes":[
                 {"role":"DEFAULT","name":"翻译","systemPrompt":"忠实翻译原文"},
                 {"role":"DEFAULT","name":"润色","systemPrompt":"保留事实，改善表达"}]}
                ""","platform-model","fixed");
        assertEquals(2,result.path("nodes").size());
        assertNotEquals(result.path("nodes").get(0).path("nodeId"),result.path("nodes").get(1).path("nodeId"));
        assertEquals("保留事实，改善表达",result.path("nodes").get(1).path("systemPrompt").asText());
    }
    @Test public void wrongModeRoleCannotBeSmuggledByModelOutput() {
        var service=new WorkspaceDraftService(null,null,new ObjectMapper(),null);
        assertThrows(Exception.class,()->service.normalize("""
                {"name":"错误骨架","nodes":[{"role":"EXECUTOR_CLIENT","name":"执行","systemPrompt":"执行任务"}]}
                ""","platform-model","fixed"));
    }
}
