package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.ModelRequestGuard;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.junit.Assert.*;

public class ModelRequestGuardTest {
    @After public void reset() { ModelRequestGuard.configure(128000, 8192); }
    @Test public void trimsWholeOldTurnsButKeepsFactsTaskAndToolSchema() throws Exception {
        ModelRequestGuard.configure(1300, 100);
        var oldUser = Map.of("role", "user", "content", "旧轮次中的历史资料。".repeat(1000));
        var oldAssistant = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "old-tool")));
        var oldTool = Map.of("role", "tool", "tool_call_id", "old-tool", "content", "old result");
        var facts = Map.of("role", "user", "content", "Date Oct 12; budget 300; latest correction");
        var task = Map.of("role", "user", "content", "Plan my trip");
        var input = new LinkedHashMap<String,Object>();
        var messages = List.of(Map.of("role", "system", "content", "policy"), oldUser, oldAssistant, oldTool, facts, task);
        input.put("messages", messages);
        input.put("tools", List.of(Map.of("name", "map_search", "schema", "keep this")));
        var result = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        assertEquals(3, result.get("messages").size());
        assertEquals(facts.get("content"), result.get("messages").get(1).get("content").asText());
        assertEquals(100, result.get("max_tokens").asInt());
        assertNotNull(result.get("tools"));
        assertEquals("persisted source history must not mutate", 6, messages.size());
    }
    @Test public void oversizedCurrentTaskFailsInsteadOfSilentlyLosingConstraints() {
        ModelRequestGuard.configure(1024, 100);
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", "must keep ".repeat(1000))));
        assertThrows(RunBudgetExceededException.class, () -> ModelRequestGuard.prepare(input, null));
    }

    @Test public void chineseContextLargerThan128kBytesDoesNotPretendToBe128kTokens() throws Exception {
        String content = "北京景点、开放时间、门票、地址。".repeat(3000);
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", content)));
        assertTrue(new ObjectMapper().writeValueAsBytes(input).length > 128000);
        var prepared = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        assertEquals(content, prepared.path("messages").get(0).path("content").asText());
    }

    @Test public void tokenizerSentinelsInUserTextRemainOrdinaryContent() throws Exception {
        String content = "解释这些字符串：<|endoftext|> <|fim_prefix|>";
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", content)));
        var prepared = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        assertEquals(content, prepared.path("messages").get(0).path("content").asText());
    }

    @Test public void verifiedModelWindowReservesOutputBeforeAdmittingInput() {
        ModelRequestGuard.configure(128000, 8192, Map.of("small-model", 4096));
        var input = new LinkedHashMap<String,Object>();
        input.put("model", "small-model"); input.put("max_tokens", 2048);
        input.put("messages", List.of(Map.of("role", "user", "content", "当前要求不可丢失。".repeat(600))));
        var failure = assertThrows(RunBudgetExceededException.class, () -> ModelRequestGuard.prepare(input, null));
        assertTrue(failure.getMessage().contains("可用 2048"));
        assertTrue(failure.getMessage().contains("当前任务和用户约束"));
    }

    @Test public void contextCompactionPreservesCallPairsFactsAndRetrievableReference() throws Exception {
        ModelRequestGuard.configure(2200, 100);
        String toolText = new ObjectMapper().writeValueAsString(Map.of(
                "tool", "maps_text_search", "resultRef", "tool:owned-result", "readTool", "inspect_tool_result",
                "summary", "长工具数据，故宫地址与详细资料。".repeat(1500), "status", "success"));
        var call = Map.of("id", "call-1", "type", "function", "function", Map.of("name", "maps_text_search", "arguments", "{}"));
        var messages = List.of(Map.of("role", "system", "content", "policy"),
                Map.of("role", "user", "content", "用户补充：两位老人，不能步行太久"),
                Map.of("role", "user", "content", "为北京四日游制定计划"),
                Map.of("role", "assistant", "tool_calls", List.of(call)),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", toolText));
        var input = new LinkedHashMap<String,Object>(); input.put("messages", messages);
        var result = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        assertEquals(messages.size(), result.path("messages").size());
        assertEquals("用户补充：两位老人，不能步行太久", result.path("messages").get(1).path("content").asText());
        assertEquals("call-1", result.path("messages").get(3).path("tool_calls").get(0).path("id").asText());
        var response = result.path("messages").get(4);
        assertEquals("call-1", response.path("tool_call_id").asText());
        var envelope = new ObjectMapper().readTree(response.path("content").asText());
        assertEquals("tool:owned-result", envelope.path("resultRef").asText());
        assertEquals("success", envelope.path("status").asText());
        assertTrue(envelope.path("contextCompacted").asBoolean());
        assertEquals(toolText, messages.get(4).get("content"));
    }

    @Test public void unreferencedCurrentToolEvidenceIsNotSilentlyDeletedToMakeRequestFit() {
        ModelRequestGuard.configure(1024, 100);
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", "保留当前任务"),
                Map.of("role", "tool", "tool_call_id", "current", "content", "未保存的原始结果".repeat(1000))));
        assertThrows(RunBudgetExceededException.class, () -> ModelRequestGuard.prepare(input, null));
    }

    @Test public void boundaryCompactionKeepsWholePoiIdentifiersAndStructuredJson() throws Exception {
        ModelRequestGuard.configure(2600, 100);
        var poi = Map.of("id", "B000A8UIN8", "name", "故宫", "location", "116.397,39.918", "url", "https://example.test/place?id=B000A8UIN8");
        var summary = Map.of("source", "amap_poi_compacted", "totalPois", 40, "pois", java.util.Collections.nCopies(40, poi));
        String original = new ObjectMapper().writeValueAsString(Map.of("resultRef", "tool:poi", "readTool", "inspect_tool_result", "summary", summary));
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", "规划旅行"), Map.of("role", "tool", "tool_call_id", "p", "content", original)));
        var result = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        var projected = new ObjectMapper().readTree(result.path("messages").get(1).path("content").asText());
        assertTrue(projected.path("summary").isObject());
        assertEquals(new ObjectMapper().valueToTree(poi), projected.path("summary").path("pois").get(0));
        assertEquals("tool:poi", projected.path("resultRef").asText());
    }

    @Test public void trimmingAnOldTurnMustNotOrphanAToolResultAfterUserClarification() {
        ModelRequestGuard.configure(1024, 100);
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(
                Map.of("role", "user", "content", "旧请求内容".repeat(1000)),
                Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "pending"))),
                Map.of("role", "user", "content", "工具等待期间补充约束"),
                Map.of("role", "tool", "tool_call_id", "pending", "content", "结果"),
                Map.of("role", "user", "content", "当前任务")));
        var failure = assertThrows(RunBudgetExceededException.class, () -> ModelRequestGuard.prepare(input, null));
        assertTrue(failure.getMessage().contains("关联"));
    }
    @Test public void completionLimitAndUnknownUsageRemainExplicit() throws Exception {
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", "hello")));
        input.put("max_completion_tokens", 123);
        var json = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        assertFalse(json.has("max_tokens"));
        assertEquals(123, json.get("max_completion_tokens").asInt());
        assertNull(ModelRequestGuard.usage(Map.of()));
        assertEquals(Long.valueOf(15), ModelRequestGuard.usage(Map.of("usage", Map.of("prompt_tokens",10,"completion_tokens",5))));
    }
    @Test public void imagePayloadIsPreservedWithoutCountingBase64AsLanguageTokens() throws Exception {
        String image = "data:image/png;base64," + "A".repeat(200000);
        var input = new LinkedHashMap<String,Object>();
        input.put("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type","image_url","image_url", Map.of("url",image)),
                Map.of("type","text","text","describe")))));
        var json = new ObjectMapper().readTree(ModelRequestGuard.prepare(input, null).body());
        assertEquals(image, json.get("messages").get(0).get("content").get(0).get("image_url").get("url").asText());
    }
    @Test public void synchronousGuardPreservesHttpContractAndSettlesRealUsage() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        var budget = cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.start(id,"u",
                new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.Limits(java.time.Duration.ofSeconds(30),3,3,10000));
        org.slf4j.MDC.put("runId", id);
        try {
            var request = new org.springframework.mock.http.client.MockClientHttpRequest();
            request.setMethod(org.springframework.http.HttpMethod.POST);
            var response = ModelRequestGuard.synchronous().intercept(request,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":100}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    (req, body) -> new org.springframework.mock.http.client.MockClientHttpResponse(
                            "{\"usage\":{\"total_tokens\":17}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), org.springframework.http.HttpStatus.OK));
            assertEquals(200, response.getStatusCode().value());
            assertEquals(17L, budget.snapshot().get("confirmedTokens"));
            assertEquals(17L, budget.snapshot().get("reservedTokens"));
            assertTrue(new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("17"));
            response.close();
        } finally { org.slf4j.MDC.remove("runId"); }
    }
    @Test public void synchronousResponseClosesWhenAccountingPreventsHandoff() {
        // Cover failure on actual settlement and failure during final cleanup after a prepared return.
        for (boolean failActual : List.of(true,false)) {
            var ledger=org.mockito.Mockito.mock(cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting.class);
            cn.bugstack.ai.domain.agent.service.model.ModelUsageAccounting.Ticket ticket=(tokens,outcome)->{
                if((tokens!=null)==failActual)throw new IllegalStateException("synthetic accounting outage");
            };
            org.mockito.Mockito.when(ledger.begin(org.mockito.ArgumentMatchers.any())).thenReturn(ticket);
            cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger.install(ledger);
            String id=java.util.UUID.randomUUID().toString();
            try {
                cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.start(id,"u",
                        new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.Limits(java.time.Duration.ofSeconds(30),3,3,10000));
                org.slf4j.MDC.put("runId",id);
                var request=new org.springframework.mock.http.client.MockClientHttpRequest();request.setMethod(org.springframework.http.HttpMethod.POST);
                var closed=new java.util.concurrent.atomic.AtomicBoolean();
                var response=new org.springframework.mock.http.client.MockClientHttpResponse(
                        "{\"usage\":{\"total_tokens\":17}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),org.springframework.http.HttpStatus.OK) {
                    @Override public void close(){closed.set(true);super.close();}
                };
                assertThrows(IllegalStateException.class,()->ModelRequestGuard.synchronous(
                        cn.bugstack.ai.domain.agent.service.model.ModelUsageContext.api("api","MAIN","https://provider.test"))
                        .intercept(request,"{\"model\":\"chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":100}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),(req,body)->response));
                assertTrue("response must be closed when it cannot be returned",closed.get());
            } finally {
                cn.bugstack.ai.domain.agent.service.model.ModelUsageLedger.uninstall(ledger);
                cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry.finish(id);org.slf4j.MDC.clear();
            }
        }
    }
}
