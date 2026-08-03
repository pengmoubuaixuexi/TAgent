package cn.bugstack.ai.domain.agent.service.evidence;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventRecord;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshot;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import com.alibaba.fastjson.JSON;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EvidenceMapServiceTest {

    @Test
    public void expiredSnapshotStillReturnsPreviouslyGeneratedMap() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find("run-expired")).thenReturn(Optional.empty());
        when(snapshots.findEvidenceMap("run-expired"))
                .thenReturn(Optional.of(new LinkedHashMap<>(Map.of("runId", "run-expired"))));
        EvidenceMapService service = new EvidenceMapService();
        ReflectionTestUtils.setField(service, "runSnapshotService", snapshots);

        Map<String, Object> result = service.generate("run-expired", "session-1", "answer", false);

        assertEquals(Boolean.TRUE, result.get("cached"));
        assertEquals(Boolean.FALSE, result.get("snapshotAvailable"));
        assertEquals(Boolean.FALSE, result.get("regenerateAvailable"));
    }

    @Test
    public void expiredSnapshotRejectsRegenerationWithoutTouchingRetainedMap() {
        RunSnapshotService snapshots = mock(RunSnapshotService.class);
        when(snapshots.find("run-expired")).thenReturn(Optional.empty());
        EvidenceMapService service = new EvidenceMapService();
        ReflectionTestUtils.setField(service, "runSnapshotService", snapshots);

        try {
            service.generate("run-expired", "session-1", "answer", true);
            fail("regeneration must require a live snapshot");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("无法重新生成"));
        }

        verify(snapshots, never()).saveEvidenceMap(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    public void markdownSectionsRemainBusinessBlocksAndKeepTablesTogether() {
        EvidenceMapService service = new EvidenceMapService();

        List<Map<String, String>> candidates = service.buildClaimCandidates("""
                # 北京三日游

                ## 出行基础信息
                - 日期：8月7日到9日
                - 目的地：北京

                ## 交通与预算
                | 项目 | 预算 |
                |---|---:|
                | 高铁往返 | 1200-1500元 |

                ## 第一天行程
                故宫 → 景山公园，步行约1.4公里。
                """);

        assertEquals(3, candidates.size());
        assertEquals("B1", candidates.get(0).get("claimId"));
        assertEquals("交通与预算", candidates.get(1).get("title"));
        assertTrue(candidates.get(1).get("text").contains("| 高铁往返 | 1200-1500元 |"));
        assertTrue(candidates.get(2).get("text").contains("步行约1.4公里"));
    }

    @Test
    public void validationKeepsEveryBlockAndDistinguishesUserAndPartialEvidence() {
        EvidenceMapService service = new EvidenceMapService();
        List<Map<String, Object>> evidences = List.of(
                evidence("user_request:1", "user_request", "帮我规划北京三日游", Map.of()),
                evidence("tool:ticket", "tool", "{\"price\":601}",
                        Map.of("input", "{\"fromStation\":\"HZH\",\"toStation\":\"BJP\"}")));
        String answer = """
                ## 出行基础信息
                目的地为北京。

                ## 高铁预算
                高铁往返约1202元。

                ## 注意事项
                提前预约热门景点。
                """;
        String output = """
                {"claims":[
                  {"claimId":"B1","links":[{"evidenceId":"user_request:1","relation":"specified_by_user","quote":"北京三日游"}]},
                  {"claimId":"B2","links":[{"evidenceId":"tool:ticket","relation":"partial_support","quote":"\\\"price\\\":601","note":"只查询到单程价格，往返金额为推导值"}]}
                ]}
                """;

        List<Map<String, Object>> claims = service.validateClaims(output, answer, evidences);

        assertEquals(3, claims.size());
        assertEquals("user_specified", claims.get(0).get("status"));
        assertEquals("partial", claims.get(1).get("status"));
        assertEquals("unsupported", claims.get(2).get("status"));
        assertEquals("部分/推导关系应保留说明", "只查询到单程价格，往返金额为推导值",
                ((Map<?, ?>) ((List<?>) claims.get(1).get("links")).get(0)).get("note"));
    }

    @Test
    public void collectionIncludesOriginalRequestAndStructuredAskUserAnswer() {
        EvidenceMapService service = new EvidenceMapService();
        RunSnapshot snapshot = RunSnapshot.builder()
                .originalMessage("帮我规划北京三日游")
                .timelineEvents(List.of(
                        event("1", "user_input_required", Map.of(
                                "inputId", "ask-1",
                                "questionDetails", List.of(Map.of("question", "什么时候出发？")))),
                        event("2", "user_input_result", Map.of(
                                "inputId", "ask-1", "status", "ANSWERED", "answer", "8月7日，高铁出行"))))
                .build();

        List<Map<String, Object>> evidences = service.collectEvidence(snapshot);

        assertEquals(2, evidences.size());
        assertEquals("user_request", evidences.get(0).get("type"));
        assertEquals("user_decision", evidences.get(1).get("type"));
        assertTrue(String.valueOf(evidences.get(1).get("content")).contains("什么时候出发"));
        assertTrue(String.valueOf(evidences.get(1).get("content")).contains("8月7日"));
    }

    @Test
    public void collectionReadsLegacyAskUserMetaAnswer() {
        EvidenceMapService service = new EvidenceMapService();
        RunSnapshot snapshot = RunSnapshot.builder()
                .timelineEvents(List.of(event("legacy", "tool_call_end", Map.of(
                        "meta", true, "toolName", "ask_user", "status", "success",
                        "detail", "用户回复：下周五出发，乘坐高铁"))))
                .build();

        List<Map<String, Object>> evidences = service.collectEvidence(snapshot);

        assertEquals(1, evidences.size());
        assertEquals("user_decision", evidences.get(0).get("type"));
        assertTrue(String.valueOf(evidences.get(0).get("content")).contains("下周五出发"));
    }

    @Test
    public void toolCatalogIncludesInputAndUnwrapsNestedJsonText() {
        EvidenceMapService service = new EvidenceMapService();
        String nestedOutput = "[{\"type\":\"text\",\"text\":\"[{\\\"train_no\\\":\\\"G1\\\",\\\"price\\\":601}]\"}]";
        Map<String, Object> tool = evidence("tool:ticket", "tool", nestedOutput,
                Map.of("input", "{\"fromStation\":\"HZH\",\"toStation\":\"BJP\"}"));

        String catalog = service.buildCatalog(List.of(tool));

        assertTrue(catalog.contains("INPUT:"));
        assertTrue(catalog.contains("HZH"));
        assertTrue(catalog.contains("train_no"));
        assertTrue(catalog.contains("601"));
        assertFalse(catalog.contains("\\\"train_no\\\""));
    }

    @Test
    public void blockUsesMultipleChecksAndMultipleExactFragments() {
        EvidenceMapService service = new EvidenceMapService();
        String answer = """
                ## Transport plan
                - Outbound train G1 costs 600.
                - Return train G2 costs 700.
                """;
        List<Map<String, Object>> evidences = List.of(
                evidence("tool:outbound", "tool", "{\"trains\":[\"G1\"],\"prices\":[600]}", Map.of()),
                evidence("tool:return", "tool", "{\"trains\":[\"G2\"],\"prices\":[700]}", Map.of()));
        String output = """
                {"claims":[{"claimId":"B1","checks":[
                  {"text":"- Outbound train G1 costs 600.","links":[{"evidenceId":"tool:outbound","relation":"supports","quotes":["G1","600"]}]},
                  {"text":"- Return train G2 costs 700.","links":[{"evidenceId":"tool:return","relation":"supports","quotes":["G2","700"]}]}
                ]}]}
                """;

        Map<String, Object> claim = service.validateClaims(output, answer, evidences).get(0);

        assertEquals("supported", claim.get("status"));
        assertEquals(2, ((List<?>) claim.get("checks")).size());
        assertEquals(2L, claim.get("coveredChecks"));
        assertEquals(2, ((List<?>) claim.get("links")).size());
        Map<?, ?> firstLink = (Map<?, ?>) ((List<?>) claim.get("links")).get(0);
        assertEquals(List.of("G1", "600"), firstLink.get("quotes"));
        assertFalse("flattened claim links must not serialize as Fastjson $ref",
                JSON.toJSONString(claim).contains("\"$ref\""));
    }

    @Test
    public void oneSupportedCheckDoesNotMarkMultiFactBlockFullySupported() {
        EvidenceMapService service = new EvidenceMapService();
        String answer = """
                ## Transport plan
                - Outbound train G1 costs 600.
                - Return train G2 costs 700.
                """;
        List<Map<String, Object>> evidences = List.of(
                evidence("tool:outbound", "tool", "{\"trains\":[\"G1\"],\"prices\":[600]}", Map.of()));
        String output = """
                {"claims":[{"claimId":"B1","checks":[
                  {"text":"- Outbound train G1 costs 600.","links":[{"evidenceId":"tool:outbound","relation":"supports","quotes":["G1","600"]}]},
                  {"text":"- Return train G2 costs 700.","links":[]}
                ]}]}
                """;

        Map<String, Object> claim = service.validateClaims(output, answer, evidences).get(0);

        assertEquals("partial", claim.get("status"));
        assertEquals(1L, claim.get("coveredChecks"));
    }

    private static RunEventRecord event(String id, String type, Map<String, Object> payload) {
        return RunEventRecord.builder()
                .eventId(id)
                .eventType(type)
                .payloadJson(JSON.toJSONString(payload))
                .build();
    }

    private static Map<String, Object> evidence(String id, String type, String content,
                                                Map<String, Object> metadata) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidenceId", id);
        item.put("type", type);
        item.put("content", content);
        item.put("metadata", metadata);
        return item;
    }
}
