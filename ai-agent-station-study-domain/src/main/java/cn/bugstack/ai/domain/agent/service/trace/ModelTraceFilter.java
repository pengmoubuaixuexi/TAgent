package cn.bugstack.ai.domain.agent.service.trace;

import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import cn.bugstack.ai.domain.agent.service.execute.common.StreamingActivityTracker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records each streaming model request of a run. Sits last before the network, so it sees the body
 * as sent (after reasoning injection and context fitting), once per retry and per substitute model.
 * The reply is recorded when the stream completes, before the model's tool loop continues.
 */
public final class ModelTraceFilter implements ExchangeFilterFunction {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

    private final String apiId;
    private final String purpose;

    public ModelTraceFilter(String apiId, String purpose) {
        this.apiId = apiId;
        this.purpose = purpose;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (!RunTrace.enabled() || request.method() != HttpMethod.POST) return next.exchange(request);
        Map<String, String> captured = ReasoningContentFilter.captureContext();
        StreamingActivityTracker.Activity activity = StreamingActivityTracker.current();
        String mdcStep = MDC.get("step");
        String mdcPurpose = MDC.get("modelPurpose");
        return Mono.deferContextual(context -> {
            String runKey = context.getOrDefault(ReasoningContentFilter.RUN_CONTEXT_KEY,
                    captured.get(ReasoningContentFilter.RUN_CONTEXT_KEY));
            RunBudgetRegistry.State budget = RunBudgetRegistry.find(runKey);
            if (budget == null) return next.exchange(request);
            StreamingActivityTracker.Activity scoped = context.getOrDefault(StreamingActivityTracker.CONTEXT_KEY, activity);
            byte[] body = ClientRequestBodies.bytes(request);
            if (body == null || body.length == 0) return next.exchange(request);
            Map<String, Object> parsed;
            try {
                parsed = JSON.readValue(body, MAP);
            } catch (Exception notJson) {
                parsed = null;
            }
            ClientRequest forward = ClientRequestBodies.withBody(request, body);
            if (parsed == null || !(parsed.get("messages") instanceof List<?>)) return next.exchange(forward);
            Recording recording = new Recording(budget.runId(), budget.ownerUserId(),
                    scoped != null ? scoped.callLabel() : mdcStep != null ? mdcStep : purpose != null ? purpose : mdcPurpose,
                    purpose != null ? purpose : mdcPurpose, parsed);
            return next.exchange(forward)
                    .map(recording::tee)
                    .doOnError(recording::failed)
                    .doOnCancel(recording::cancelledBeforeResponse);
        });
    }

    private final class Recording {
        private final String runId;
        private final String owner;
        private final String step;
        private final String purpose;
        private final Map<String, Object> request;
        private final long started = System.currentTimeMillis();
        private final AtomicBoolean responded = new AtomicBoolean();
        private final AtomicBoolean recorded = new AtomicBoolean();

        Recording(String runId, String owner, String step, String purpose, Map<String, Object> request) {
            this.runId = runId;
            this.owner = owner;
            this.step = step;
            this.purpose = purpose;
            this.request = request;
        }

        ClientResponse tee(ClientResponse response) {
            responded.set(true);
            int status = response.statusCode().value();
            ModelReplies.Stream stream = new ModelReplies.Stream();
            Flux<DataBuffer> body = response.body(BodyExtractors.toDataBuffers())
                    .map(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        stream.accept(bytes);
                        return BUFFERS.wrap(bytes);
                    })
                    // Before completion reaches the model client: its tool loop continues from onComplete.
                    .doOnComplete(() -> finished(status, stream, null))
                    .doOnError(error -> finished(status, stream, error))
                    // The client stops reading after [DONE]; earlier, the user or a deadline stopped the stream.
                    .doOnCancel(() -> finished(status, stream, null));
            return ClientResponse.from(response).body(body).build();
        }

        private void finished(int status, ModelReplies.Stream stream, Throwable error) {
            stream.finish();
            boolean ok = status >= 200 && status < 300;
            String outcome = error != null || !ok || stream.error() != null ? RunTrace.OUTCOME_ERROR
                    : stream.done() ? RunTrace.OUTCOME_OK : RunTrace.OUTCOME_CANCELLED;
            String detail = error != null ? describe(error) : stream.error() != null ? stream.error() : ok ? null : stream.raw();
            record(status, stream.reply(), outcome, detail);
        }

        void failed(Throwable error) {
            if (!responded.get()) record(0, null, RunTrace.OUTCOME_ERROR, describe(error));
        }

        void cancelledBeforeResponse() {
            if (!responded.get()) record(0, null, RunTrace.OUTCOME_CANCELLED, null);
        }

        private void record(int status, RunTrace.ModelReply reply, String outcome, String error) {
            if (!recorded.compareAndSet(false, true)) return;
            RunTrace.model(new RunTrace.ModelExchange(runId, owner, step, apiId, purpose, started,
                    System.currentTimeMillis() - started, request, status, reply, outcome, error));
        }
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
