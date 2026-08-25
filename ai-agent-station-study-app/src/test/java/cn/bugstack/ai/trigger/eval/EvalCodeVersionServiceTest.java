package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCodeVersion;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;

public class EvalCodeVersionServiceTest {

    @Test
    public void shouldCaptureFilteredGitTreeWithoutChangingRealIndex() throws Exception {
        String indexTreeBefore = gitIndexTree();
        EvalCodeVersionService service = new EvalCodeVersionService(mock(IAiEvalOpsDao.class));
        ReflectionTestUtils.setField(service, "configuredRepositoryPath", System.getProperty("user.dir"));

        service.initializeRuntimeSnapshot();
        AiEvalCodeVersion snapshot = service.createRunSnapshot("test-run", LocalDateTime.now());

        Assert.assertNotNull(snapshot.getSourceTreeHash());
        Assert.assertEquals(40, snapshot.getSourceTreeHash().length());
        Assert.assertEquals("GIT_TREE_SHA1", snapshot.getHashAlgorithm());
        Assert.assertNotEquals("CAPTURE_UNAVAILABLE", snapshot.getBindingStatus());
        Assert.assertEquals(indexTreeBefore, gitIndexTree());
    }

    @Test
    public void shouldIgnoreLogsBuildOutputAndReportsButKeepSource() {
        List<String> patterns = List.of(
                "**/target/**", "**/*.log", "logs/**", "docs/dev-ops/test/e2e-*-report*",
                "docs/dev-ops/test/e2e-*-trace*/**"
        );

        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("logs/app.log", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("module/target/classes/App.class", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest("docs/dev-ops/test/e2e-100-report.md", patterns));
        Assert.assertTrue(EvalCodeVersionService.ignoredForTest(
                "docs/dev-ops/test/e2e-100-trace-2026-08-22/Q1.json", patterns));
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
