package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.domain.agent.service.IAgentDispatchService;
import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunStepSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.ToolEvidenceRecord;
import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.adapter.repository.RedisRunSnapshotService;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCase;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCaseResult;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalRun;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class EvalTraceCodecTest {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper();

    @Test
    public void reproducesLegacyWriterBugAndWritesIndependentSnapshotValues() throws Exception {
        Map<String, Object> trace = traceFixture();
        String legacy = JSON.toJSONString(trace);
        // This is the same map + bean + repeated-list shape as EvalRunService, not a
        // hand-written invalid fixture. Standard JSON consumers must be able to read it.
        assertThrows(JsonProcessingException.class, () -> STRICT_JSON.readTree(legacy));
        JsonNode fixed = STRICT_JSON.readTree(EvalTraceCodec.write(trace));
        assertEquals(fixed.get("steps"), fixed.path("runSnapshot").get("steps"));
        assertEquals(fixed.get("toolEvidences"), fixed.path("runSnapshot").get("toolEvidences"));
        assertEquals("实际工具证据", fixed.path("runSnapshot").path("toolEvidences").get(0).path("output").asText());
        assertFalse(EvalTraceCodec.write(trace).contains("\"$ref\""));
    }

    @Test
    public void readsHistoricalMalformedTraceWithoutOverwritingSourceOrLosingJudgeEvidence() throws Exception {
        String source = JSON.toJSONString(traceFixture());
        JSONObject restored = EvalJudgeService.parseTrace(source);
        assertEquals(restored.getJSONArray("steps"), restored.getJSONObject("runSnapshot").getJSONArray("steps"));
        assertEquals(restored.getJSONArray("toolEvidences"), restored.getJSONObject("runSnapshot").getJSONArray("toolEvidences"));
        assertEquals(2, restored.getJSONObject("traceReadCompatibility").getJSONArray("restoredFields").size());
        assertTrue(restored.getJSONObject("traceReadCompatibility").getBooleanValue("sourceUnchanged"));
        assertTrue(EvalJudgeService.extractToolContext(restored).contains("实际工具证据"));
        assertTrue(EvalJudgeService.extractStepContext(restored).contains("已执行步骤内容"));
        assertThrows(JsonProcessingException.class, () -> STRICT_JSON.readTree(source));
        STRICT_JSON.readTree(EvalTraceCodec.write(restored));
    }

    @Test
    public void preservesLiteralReferenceFieldsAndReferenceLikeText() {
        String source = """
                {"traceSchemaVersion":"eval-trace-v3-json-values","steps":[],
                 "literal":{"$ref":"$.steps"},"finalAnswer":"{\\"$ref\\":\\"$.case.config.expectedCapabilities.steps\\"}"}
                """;
        JSONObject result = EvalTraceCodec.read(source);
        assertEquals("$.steps", result.getJSONObject("literal").getString("$ref"));
        assertEquals("{\"$ref\":\"$.case.config.expectedCapabilities.steps\"}", result.getString("finalAnswer"));
        assertFalse(result.containsKey("traceReadCompatibility"));
    }

    @Test
    public void writesIntegerDagKeysAsJsonStringsAndReadsLegacyDagWithoutChangingEdges() throws Exception {
        Map<String, Object> fixture = traceFixture();
        RunSnapshot snapshot = (RunSnapshot) fixture.get("runSnapshot");
        snapshot.getFlowPlanDependencies().put(1, Set.of());
        snapshot.getFlowPlanDependencies().put(2, Set.of(1));
        snapshot.getFlowPlanDependencies().put(3, Set.of(1, 2));
        JsonNode fixed = STRICT_JSON.readTree(EvalTraceCodec.write(fixture));
        JSONObject restored = EvalTraceCodec.read(JSON.toJSONString(fixture));
        JsonNode recovered = STRICT_JSON.readTree(EvalTraceCodec.write(restored));
        assertEquals(fixed.path("runSnapshot").get("flowPlanDependencies"),
                recovered.path("runSnapshot").get("flowPlanDependencies"));
        assertEquals(3, restored.getJSONObject("traceReadCompatibility").getJSONArray("restoredFields").size());
        assertInvalid("{\"traceSchemaVersion\":\"eval-trace-v2-old-compatible\","
                + "\"runSnapshot\":{\"flowPlanDependencies\":{1:[],1:[2]}}}");
        assertInvalid("{\"traceSchemaVersion\":\"eval-trace-v2-old-compatible\","
                + "\"runSnapshot\":{\"flowPlanDependencies\":{name:[]}}}");
        assertInvalid("{\"traceSchemaVersion\":\"eval-trace-v2-old-compatible\","
                + "\"runSnapshot\":{\"flowPlanDependencies\":{1:[\"not-a-step\"]}}}");
        assertInvalid("{\"flowPlanDependencies\":{1:[]}}");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void writesSnapshotAfterActualRedisSaveAndReadIncludingPollutedJSONObjectKeys() throws Exception {
        RunSnapshot source = (RunSnapshot) traceFixture().get("runSnapshot");
        source.setRunId("redis-run-1");
        source.getFlowPlanDependencies().put(1, Set.of());
        source.getFlowPlanDependencies().put(2, Set.of(1));
        source.getFlowPlanDependencies().put(3, Set.of(1, 2));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
        Map<String, String> storage = new LinkedHashMap<>();
        doAnswer(call -> { storage.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.getAndExpire(anyString(), any(Duration.class))).thenAnswer(call -> storage.get(call.getArgument(0)));
        when(hashes.entries(anyString())).thenReturn(Map.of("tool-1", JSON.toJSONString(source.getToolEvidences().get(0))));
        RedisRunSnapshotService snapshots = new RedisRunSnapshotService();
        ReflectionTestUtils.setField(snapshots, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(snapshots, "keyPrefix", "agent:run:snapshot:");
        ReflectionTestUtils.setField(snapshots, "ttlSeconds", 21600L);
        ReflectionTestUtils.invokeMethod(snapshots, "save", source);
        RunSnapshot restored = snapshots.find(source.getRunId()).orElseThrow();
        Map<?, ?> dependencies = restored.getFlowPlanDependencies();
        assertTrue("Regression requires Fastjson's JSONObject with non-String keys", dependencies instanceof JSONObject);
        assertTrue(dependencies.keySet().iterator().next() instanceof Integer);
        assertThrows(JsonProcessingException.class, () -> STRICT_JSON.writeValueAsString(restored));

        Map<String, Object> trace = traceFixture();
        trace.put("steps", restored.getSteps());
        trace.put("toolEvidences", restored.getToolEvidences());
        trace.put("runSnapshot", restored);
        JsonNode saved = STRICT_JSON.readTree(EvalTraceCodec.write(trace));
        JsonNode dag = saved.path("runSnapshot").path("flowPlanDependencies");
        assertEquals(3, dag.size());
        assertEquals(0, dag.path("1").size());
        assertEquals(1, dag.path("2").get(0).intValue());
        assertEquals(Set.of(1, 2), Set.of(dag.path("3").get(0).intValue(), dag.path("3").get(1).intValue()));
        assertEquals(saved.get("steps"), saved.path("runSnapshot").get("steps"));
        assertEquals(saved.get("toolEvidences"), saved.path("runSnapshot").get("toolEvidences"));
        assertEquals("实际工具证据", saved.path("toolEvidences").get(0).path("output").asText());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void rejectsNormalizedKeyCollisionsAndUnsupportedKeysWithoutDroppingEvidence() {
        Map<Object, Object> backing = new LinkedHashMap<>();
        backing.put(1, "numeric evidence");
        backing.put("1", "string evidence");
        JSONObject polluted = new JSONObject((Map) backing);
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.write(Map.of("nested", List.of(polluted))));
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.write(Map.of("nested", Map.of(new Object(), "evidence"))));
        Map<Object, Object> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "evidence");
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.write(Map.of("nested", nullKey)));
    }

    @Test
    public void rejectsExcessiveNestingAndCyclicSnapshotsRatherThanWritingReferences() {
        assertInvalid("{\"nested\":" + "[".repeat(260) + "0" + "]".repeat(260) + "}");
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.write(cycle));
        List<Object> listCycle = new ArrayList<>();
        listCycle.add(listCycle);
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.write(Map.of("nested", listCycle)));
        Object deep = "leaf";
        for (int i = 0; i < 260; i++) deep = List.of(deep);
        Map<String, Object> deepTrace = Map.of("nested", deep);
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.write(deepTrace));
    }

    @Test
    public void refusesUnverifiedRepairsAndOtherInvalidJson() {
        String legacy = JSON.toJSONString(traceFixture());
        assertInvalid(legacy.replace("eval-trace-v2-old-compatible", "unrecognized-schema"));
        assertInvalid(legacy.replace("$.case.config.expectedCapabilities.steps", "$.unrelated.steps"));
        assertInvalid(legacy.replace("\"steps\":[", "\"missingSteps\":["));
        assertInvalid(legacy.substring(0, legacy.length() - 1));
        assertInvalid("{\"steps\":[],\"steps\":[1]}");
        assertInvalid("{} {}");
        assertInvalid("[]");
        assertInvalid("null");
    }

    @Test
    public void doesNotRepairOrphansInsideAnotherObjectOrInsideSnapshotChild() {
        String legacy = JSON.toJSONString(traceFixture());
        assertInvalid(legacy.replace("\"runSnapshot\":", "\"otherSnapshot\":"));
        assertInvalid("{\"traceSchemaVersion\":\"eval-trace-v2-old-compatible\",\"steps\":[],"
                + "\"runSnapshot\":{\"child\":{\"status\":\"FAILED\"{\"$ref\":\"$.case.config.expectedCapabilities.steps\"}}}}");
    }

    @Test
    public void actualCaseCompletionPersistsStandardJsonAndDetailedViewReadsLegacyTrace() throws Exception {
        IAiEvalOpsDao dao = mock(IAiEvalOpsDao.class);
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        RunSnapshot snapshot = (RunSnapshot) traceFixture().get("runSnapshot");
        snapshot.getFlowPlanDependencies().put(1, Set.of());
        snapshot.getFlowPlanDependencies().put(2, Set.of(1));
        RunSnapshot restored = JSON.parseObject(JSON.toJSONString(snapshot), RunSnapshot.class);
        when(snapshots.find(anyString())).thenReturn(Optional.of(restored));
        AiEvalCase evalCase = AiEvalCase.builder().caseId("case-1").question("测试问题").sequenceNo(1)
                .configJson("{}").build();
        EvalRunService service = new EvalRunService(dao, new EvalDatasetService(dao), new EvalRuleEngine(),
                mock(IAgentDispatchService.class), mock(RunEventPublisher.class), snapshots, mock(EvalCodeVersionService.class));
        try {
            ReflectionTestUtils.invokeMethod(service, "executeCase",
                    AiEvalRun.builder().evalRunId("eval-1").userId("owner").tenantId("default").build(), evalCase,
                    AiEvalCaseResult.builder().resultId("result-1").build(), "session-1");
            ArgumentCaptor<AiEvalCaseResult> finished = ArgumentCaptor.forClass(AiEvalCaseResult.class);
            verify(dao).finishCaseResult(finished.capture());
            JsonNode saved = STRICT_JSON.readTree(finished.getValue().getTraceJson());
            assertEquals("eval-trace-v3-json-values", saved.path("traceSchemaVersion").asText());
            assertEquals("COMPLETE", saved.path("traceCompleteness").asText());
            assertEquals(1, saved.path("runSnapshot").path("flowPlanDependencies").path("2").get(0).intValue());
            assertEquals(saved.get("steps"), saved.path("runSnapshot").get("steps"));
            assertEquals("ERROR", finished.getValue().getStatus());

            AiEvalCaseResult oldResult = AiEvalCaseResult.builder().resultId("old-result").evalRunId("eval-1")
                    .caseId("case-1").traceJson(JSON.toJSONString(traceFixture())).build();
            when(dao.findCaseResult("old-result")).thenReturn(oldResult);
            when(dao.findCase("case-1")).thenReturn(evalCase);
            Map<String, Object> detail = service.getResult("eval-1", "old-result");
            assertTrue(detail.get("trace") instanceof JSONObject);
            assertFalse(detail.containsKey("traceError"));
            verify(dao, times(1)).finishCaseResult(any());
        } finally {
            service.shutdown();
        }
    }

    private static void assertInvalid(String value) {
        assertThrows(IllegalArgumentException.class, () -> EvalTraceCodec.read(value));
    }

    private static Map<String, Object> traceFixture() {
        RunSnapshot snapshot = RunSnapshot.builder().status("FAILED").agentId("8013").agentType("FLOW").build();
        snapshot.getSteps().add(RunStepSnapshot.builder().stepId("step1").title("执行步骤")
                .status("FAILED").content("已执行步骤内容").build());
        snapshot.getToolEvidences().add(ToolEvidenceRecord.builder().toolName("calculator")
                .status("success").output("实际工具证据").build());
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceSchemaVersion", "eval-trace-v2-old-compatible");
        trace.put("case", Map.of("config", new EvalCaseConfig().normalized()));
        trace.put("steps", snapshot.getSteps());
        trace.put("toolEvidences", snapshot.getToolEvidences());
        trace.put("runSnapshot", snapshot);
        return trace;
    }
}
