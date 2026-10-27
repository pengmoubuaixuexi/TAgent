package cn.bugstack.ai.test.sandbox;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.sandbox.ISandboxService;
import cn.bugstack.ai.infrastructure.adapter.repository.attachment.ChatFileService;
import cn.bugstack.ai.trigger.sandbox.SandboxClient;
import cn.bugstack.ai.trigger.sandbox.SandboxService;
import cn.bugstack.ai.trigger.workspace.WorkspaceOutcomeService;
import cn.bugstack.ai.trigger.workspace.WorkspaceRunArtifactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** run_python against a fake runner: submission, progress, outputs saved as the user's artifacts, failures. */
public class SandboxServiceTest {

    private static final String TOKEN = "test-token-0123456789abcdefghij";
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final List<String> submissions = new ArrayList<>();
    private final AtomicInteger polls = new AtomicInteger();
    private volatile String finalResult;
    private volatile int queuedPolls = 1;
    private volatile String deletedRun;
    private ChatFileService files;
    private WorkspaceOutcomeService outcomes;
    private WorkspaceRunArtifactService artifacts;
    private SandboxService service;
    private final String run = "run-" + System.nanoTime();

    @Before
    public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        files = mock(ChatFileService.class);
        outcomes = mock(WorkspaceOutcomeService.class);
        artifacts = mock(WorkspaceRunArtifactService.class);
        service = new SandboxService(new SandboxClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN),
                files, outcomes, artifacts);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "pollIntervalMs", 100L);
        finalResult = "{\"status\":\"OK\",\"exitCode\":0,\"durationMs\":1200,\"stdout\":\"月度合计 4200\\n\",\"stderr\":\"\","
                + "\"outputs\":[{\"index\":0,\"name\":\"趋势.png\",\"size\":4}],\"skippedOutputs\":[]}";
        RunBudgetRegistry.start(run, "alice", new RunBudgetRegistry.Limits(Duration.ofMinutes(5), 100, 2, 100000));
    }

    @After
    public void teardown() {
        server.stop(0);
        RunBudgetRegistry.finish(run);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            reply(exchange, 401, "{}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod()) && path.equals("/v1/executions")) {
            submissions.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 202, "{\"executionId\":\"exec-1\",\"state\":\"queued\",\"position\":2}");
        } else if (path.equals("/v1/executions/exec-1")) {
            int n = polls.incrementAndGet();
            if (n <= queuedPolls) reply(exchange, 200, "{\"state\":\"queued\",\"position\":2}");
            else if (n == queuedPolls + 1) reply(exchange, 200, "{\"state\":\"running\",\"runningMs\":6000}");
            else reply(exchange, 200, "{\"state\":\"finished\",\"result\":" + finalResult + "}");
        } else if (path.equals("/v1/executions/exec-1/outputs/0")) {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        } else if ("DELETE".equals(exchange.getRequestMethod()) && path.startsWith("/v1/runs/")) {
            deletedRun = path.substring("/v1/runs/".length());
            reply(exchange, 200, "{\"removed\":true}");
        } else {
            reply(exchange, 404, "{}");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Map<String, Object> scope() {
        Map<String, Object> scope = new HashMap<>();
        scope.put("userId", "alice");
        scope.put("sessionId", "s1");
        scope.put("agent.run_id", run);
        return scope;
    }

    @Test
    public void runSubmitsCodeAndInputsReportsProgressAndSavesOutputsAsArtifacts() throws Exception {
        Path input = Files.createTempFile("sales", ".csv");
        Files.writeString(input, "月份,金额\n1月,100\n");
        when(files.sandboxInput("alice", "s1", "f_sales")).thenReturn(new ChatFileService.SandboxInput("销售.csv", input, false));
        Map<String, Object> saved = new LinkedHashMap<>(Map.of("artifactId", "art-png", "revision", 1, "created", true));
        when(outcomes.createFileFromTool(eq("alice"), eq(run), eq("call-1#0"), eq("趋势.png"), eq("image/png"), any())).thenReturn(saved);
        when(artifacts.announce(eq(run), eq("s1"), eq(saved))).thenReturn(Map.of("artifactId", "art-png", "revision", 1,
                "previewUrl", "/outcomes.html?artifactId=art-png"));
        List<String> progress = new ArrayList<>();
        String result = service.run("call-1", "{\"code\":\"```python\\nprint(1)\\n```\",\"inputFiles\":[\"f_sales\"],\"purpose\":\"按月汇总\"}",
                scope(), new ISandboxService.Progress() {
                    @Override public void queued(int ahead) { progress.add("queued " + ahead); }
                    @Override public void running(long elapsedMs) { progress.add("running " + elapsedMs); }
                });
        JsonNode body = json.readTree(result);
        assertTrue(result, body.path("ok").asBoolean());
        assertEquals("月度合计 4200\n", body.path("stdout").asText());
        assertEquals("art-png", body.path("files").get(0).path("artifactId").asText());
        assertTrue(body.path("notice").asText().contains("只作为数据使用"));
        assertEquals(List.of("queued 2", "running 6000"), progress);
        String multipart = submissions.get(0);
        assertTrue("the code fence is removed", multipart.contains("\r\n\r\nprint(1)\n\r\n"));
        assertTrue(multipart.contains("filename=\"销售.csv\""));
        assertTrue(multipart.contains("月份,金额"));
        assertTrue(multipart.contains("\"kind\":\"python\""));
        assertFalse("the runner never sees real ids", multipart.contains(run) || multipart.contains("\"alice\""));
        verify(outcomes).createFileFromTool(eq("alice"), eq(run), eq("call-1#0"), eq("趋势.png"), eq("image/png"),
                argThat(bytes -> bytes.length == 4));
        Files.deleteIfExists(input);
    }

    @Test
    public void busyErrorsAndDebugRunsAreReportedWithoutSavingFiles() throws Exception {
        finalResult = "{\"status\":\"BUSY\",\"message\":\"沙箱繁忙，排队超过 60 秒仍未开始，请稍后重试\"}";
        JsonNode busy = json.readTree(service.run("c", "{\"code\":\"print(1)\"}", scope(), null));
        assertEquals("SANDBOX_BUSY", busy.path("error").asText());

        polls.set(0);
        finalResult = "{\"status\":\"ERROR\",\"exitCode\":1,\"stdout\":\"\",\"stderr\":\"ZeroDivisionError\","
                + "\"outputs\":[{\"index\":0,\"name\":\"a.png\",\"size\":4}],\"skippedOutputs\":[{\"name\":\"big.bin\",\"reason\":\"单个文件超过 10 MB\"}]}";
        Map<String, Object> debug = scope();
        debug.put("agent.debug_read_only", true);
        JsonNode failed = json.readTree(service.run("c2", "{\"code\":\"1/0\"}", debug, null));
        assertFalse(failed.path("ok").asBoolean());
        assertEquals("ERROR", failed.path("status").asText());
        assertTrue(failed.path("stderr").asText().contains("ZeroDivisionError"));
        assertFalse(failed.path("files").get(0).path("saved").asBoolean());
        assertEquals("big.bin", failed.path("skippedFiles").get(0).path("name").asText());
        verifyNoInteractions(outcomes);
    }

    @Test
    public void invalidCallsAreRejectedBeforeReachingTheRunner() throws Exception {
        assertEquals("INVALID_ARGUMENTS", json.readTree(service.run("c", "{\"code\":\"\"}", scope(), null)).path("error").asText());
        assertEquals("INVALID_ARGUMENTS", json.readTree(service.run("c", "not json", scope(), null)).path("error").asText());
        when(files.sandboxInput(anyString(), anyString(), anyString())).thenThrow(new ChatFileService.Rejected("附件 f_x 不在当前对话中"));
        JsonNode foreign = json.readTree(service.run("c", "{\"code\":\"print(1)\",\"inputFiles\":[\"f_x\"]}", scope(), null));
        assertEquals("INVALID_INPUT_FILE", foreign.path("error").asText());
        assertEquals("SANDBOX_SCOPE_UNAVAILABLE", json.readTree(service.run("c", "{\"code\":\"print(1)\"}", Map.of(), null)).path("error").asText());
        assertTrue(submissions.isEmpty());
        ReflectionTestUtils.setField(service, "enabled", false);
        assertFalse(service.available());
        assertEquals("SANDBOX_UNAVAILABLE", json.readTree(service.run("c", "{\"code\":\"print(1)\"}", scope(), null)).path("error").asText());
    }

    @Test
    public void cancellingTheRunStopsWaiting() {
        queuedPolls = 1000;
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            RunBudgetRegistry.find(run).cancel();
        }).start();
        try {
            service.run("c", "{\"code\":\"print(1)\"}", scope(), null);
            fail("expected cancellation");
        } catch (CancellationException expected) {
            assertTrue(polls.get() < 50);
        }
    }

    @Test
    public void anUnreachableRunnerIsExplainedInPlainWords() throws Exception {
        server.stop(0);
        JsonNode tool = json.readTree(service.run("c", "{\"code\":\"print(1)\"}", scope(), null));
        assertEquals("SANDBOX_UNAVAILABLE", tool.path("error").asText());
        for (ThrowingRunnable page : List.<ThrowingRunnable>of(() -> service.runCode("alice", "print(1)"),
                () -> service.convertMarkdown("alice", "# 标题", "报告", "pdf"))) {
            IOException failure = assertThrows(IOException.class, page);
            assertEquals("代码沙箱暂时不可用，请稍后再试", failure.getMessage());
        }
    }

    @Test
    public void releaseRunDeletesTheHashedRunDirectory() throws Exception {
        service.releaseRun(run);
        for (int i = 0; i < 50 && deletedRun == null; i++) Thread.sleep(20);
        assertNotNull(deletedRun);
        assertTrue(deletedRun.matches("r-[0-9a-f]{30}"));
    }
}
