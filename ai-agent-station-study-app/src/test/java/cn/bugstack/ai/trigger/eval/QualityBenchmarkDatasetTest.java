package cn.bugstack.ai.trigger.eval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QualityBenchmarkDatasetTest {

    private static final List<String> RULE_DIMENSIONS = List.of(
            "route", "answer", "step", "tool", "grounding", "memory", "stability", "efficiency", "safety");
    private static final List<String> JUDGE_DIMENSIONS = List.of(
            "correctness", "relevance", "completeness", "usefulness", "safety");

    @Test
    public void shouldCoverAllRuleAndJudgeDimensionsWithExplicitScenarioCases() throws Exception {
        ClassPathResource resource = new ClassPathResource("eval/agent-14d-scenario.json");
        JSONArray rows = JSON.parseArray(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        Assert.assertEquals(80, rows.size());
        Set<String> stableKeys = new HashSet<>();
        Set<String> groups = new HashSet<>();
        Map<String, Integer> coverage = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Assert.assertTrue("stableKey must be unique", stableKeys.add(row.getString("stableKey")));
            groups.add(row.getString("conversationGroup"));
            Assert.assertFalse(row.getString("question").isBlank());
            Assert.assertFalse(row.getString("referenceAnswer").isBlank());
            EvalCaseConfig config = EvalDatasetService.benchmarkConfig(row);
            Assert.assertNotNull(config);
            Assert.assertFalse(config.normalized().getExpectedCapabilities().isEmpty());
            for (String tag : row.getJSONArray("tags").toJavaList(String.class)) coverage.merge(tag, 1, Integer::sum);
        }

        Assert.assertEquals(16, groups.size());
        for (String dimension : RULE_DIMENSIONS) {
            Assert.assertTrue("missing rule dimension: " + dimension, coverage.getOrDefault("rule:" + dimension, 0) > 0);
        }
        for (String dimension : JUDGE_DIMENSIONS) {
            Assert.assertTrue("missing judge dimension: " + dimension, coverage.getOrDefault("judge:" + dimension, 0) > 0);
        }
        Assert.assertEquals(4, rows.stream()
                .map(item -> (JSONObject) item)
                .filter(item -> "01-profile-memory".equals(item.getString("conversationGroup"))).count());
    }
}
