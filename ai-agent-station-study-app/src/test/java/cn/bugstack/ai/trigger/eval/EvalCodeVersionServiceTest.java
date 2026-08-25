package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCodeVersion;
import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

public class EvalCodeVersionServiceTest {

    @Test
    public void shouldCaptureFilteredGitTreeWithoutChangingRealIndex() throws Exception {
        String indexTreeBefore = gitIndexTree();
        EvalCodeVersionService service = new EvalCodeVersionService(mock(IAiEvalOpsDao.class));
        ReflectionTestUtils.setField(service, "configuredRepositoryPath", System.getProperty("user.dir"));

        ReflectionTestUtils.setField(service, "runtimeSnapshot", Map.of(
                "available", true,
                "dirty", false,
                "sourceTreeHash", "stale-application-start-snapshot",
                "headCommit", "stale-head",
                "headTags", List.of("stale-tag"),
                "ignorePatterns", List.of()
        ));
        AiEvalCodeVersion snapshot = service.createRunSnapshot("test-run", LocalDateTime.now());

        Assert.assertNotNull(snapshot.getSourceTreeHash());
        Assert.assertEquals(40, snapshot.getSourceTreeHash().length());
        Assert.assertNotEquals("stale-application-start-snapshot", snapshot.getSourceTreeHash());
        Assert.assertNotEquals("stale-head", snapshot.getCapturedHeadSha());
        Assert.assertEquals("evalops-v2", snapshot.getScopeVersion());
        Assert.assertEquals("RUN_CREATE",
                JSON.parseObject(snapshot.getSnapshotJson()).getString("captureTrigger"));
        Assert.assertEquals("GIT_TREE_SHA1", snapshot.getHashAlgorithm());
        Assert.assertNotEquals("CAPTURE_UNAVAILABLE", snapshot.getBindingStatus());
        Assert.assertEquals(indexTreeBefore, gitIndexTree());
    }

    @Test
    public void shouldIgnoreLogsBuildOutputAndReportsButKeepSource() {
        List<String> patterns = List.of(
                "**/target/**", "**/*.log", "logs/**", "docs/dev-ops/test/e2e-*-report*",
                "docs/dev-ops/test/e2e-*-trace*/**",
                "ai-agent-station-study-app/src/main/resources/application-dev.yml",
                "docs/dev-ops/docker-compose-*.yml", "面试/**"
        );

        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("logs/app.log", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("module/target/classes/App.class", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("docs/dev-ops/test/e2e-100-report.md", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest(
                "docs/dev-ops/test/e2e-100-trace-2026-08-22/Q1.json", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest(
                "ai-agent-station-study-app/src/main/resources/application-dev.yml", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest(
                "docs/dev-ops/docker-compose-environment.yml", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("面试/plot_results.py", patterns));
        Assert.assertFalse(EvalCodeVersionService.ignoredForTest("module/src/main/java/App.java", patterns));
    }

    private String gitIndexTree() throws Exception {
        Process process = new ProcessBuilder("git", "-C", System.getProperty("user.dir"), "write-tree")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        Assert.assertEquals("git write-tree failed: " + output, 0, process.waitFor());
        return output;
    }
}
