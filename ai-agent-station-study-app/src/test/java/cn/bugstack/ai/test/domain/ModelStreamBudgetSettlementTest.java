package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import org.junit.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

public class ModelStreamBudgetSettlementTest {
    @Test public void wrappedBudgetFailureRetainsItsReasonAndCannotBeRetried() {
        var budget = new RunBudgetExceededException("任务剩余 Token 预算不足：已确认 420312，上限 500000");
        var wrapped = new java.io.IOException("Stream processing failed", budget);
        assertFalse(StreamingRetryPolicy.canRetry(wrapped, false, 0, 1, 3));
        var surfaced = StreamingRetryPolicy.failed("flow_step4_execute_step_5", wrapped);
        assertTrue(surfaced.getMessage().contains(budget.getMessage()));
        assertSame(wrapped, surfaced.getCause());
    }

    @Test public void completedUsageIsSettledBeforeRecursiveRequestReservesAgain() throws Exception {
        String run = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(run, "owner",
                new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 5, 5, 10000));
        try (var scope = ReasoningContentFilter.scopeCall("session", run)) {
            var request = ClientRequest.create(HttpMethod.POST, URI.create("https://provider.test/v1/chat/completions"))
                    .body(BodyInserters.fromValue(Map.of("model", "test", "messages",
                            List.of(Map.of("role", "user", "content", "hello"))))).build();
            new ReasoningContentFilter().filter(request, r -> Mono.just(ClientResponse.create(HttpStatus.OK)
                            .body("data: {\"usage\":{\"total_tokens\":123},\"choices\":[]}\n\ndata: [DONE]\n\n").build()))
                    .flatMap(response -> response.bodyToMono(String.class).then(Mono.fromRunnable(() -> {
                        // Recursive model tool loops subscribe from onComplete, before doFinally.
                        assertEquals(123L, budget.snapshot().get("confirmedTokens"));
                        budget.reserveModel(500, 8192).settle(200L);
                    }))).block(Duration.ofSeconds(5));
            assertEquals(323L, budget.snapshot().get("reservedTokens"));
            assertEquals(0, budget.snapshot().get("unknownUsageCalls"));
        } finally { RunBudgetRegistry.finish(run); ReasoningContentFilter.clearRun(run); }
    }

    @Test public void exhaustedBudgetExplainsConfirmedAndHeldAmounts() {
        String run = UUID.randomUUID().toString();
        var budget = RunBudgetRegistry.start(run, "owner",
                new RunBudgetRegistry.Limits(Duration.ofSeconds(30), 5, 5, 1000));
        try {
            budget.reserveModel(100, 100).settle(150L);
            budget.reserveModel(100, 100).settle(null);
            budget.reserveModel(200, 200);
            var error = assertThrows(RunBudgetExceededException.class, () -> budget.reserveModel(200, 100));
            assertTrue(error.getMessage().contains("1000"));
            assertTrue(error.getMessage().contains("150"));
            assertTrue(error.getMessage().contains("600"));
            assertTrue(error.getMessage().contains("300"));
            assertEquals(3, budget.snapshot().get("modelCalls"));
        } finally { RunBudgetRegistry.finish(run); }
    }
}
