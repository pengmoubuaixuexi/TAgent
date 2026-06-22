package cn.bugstack.ai.test.contract;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test-only ChatModel that records the final Prompt and never performs network I/O. */
public final class CapturingChatModel implements ChatModel {

    private final Deque<ChatResponse> cannedResponses = new ArrayDeque<>();
    private final List<WireSnapshot> snapshots = new ArrayList<>();
    private String stableId = "UNSET";

    public CapturingChatModel forStableId(String stableId) {
        this.stableId = stableId;
        return this;
    }

    public CapturingChatModel enqueue(String assistantText) {
        cannedResponses.add(response(assistantText));
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        snapshots.add(WireSnapshot.from(stableId, prompt));
        return cannedResponses.isEmpty() ? response("fixture-response") : cannedResponses.removeFirst();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    public List<WireSnapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    public WireSnapshot lastSnapshot() {
        if (snapshots.isEmpty()) throw new IllegalStateException("no request captured");
        return snapshots.get(snapshots.size() - 1);
    }

    public void clear() {
        snapshots.clear();
        cannedResponses.clear();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text == null ? "" : text))));
    }
}
