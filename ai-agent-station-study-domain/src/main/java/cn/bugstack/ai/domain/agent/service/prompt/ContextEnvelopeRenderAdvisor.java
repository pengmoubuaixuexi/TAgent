package cn.bugstack.ai.domain.agent.service.prompt;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2-B-2：统一渲染 LTM/Episodic/runtime context envelope。
 *
 * <p>LTM/Episodic advisor 只负责把结构化 section 写入 {@code request.context()}；
 * 本 advisor 在它们之后、RAG/ChatMemory 之前，把 section 与原始用户任务渲染成单个 canonical envelope。
 * 无 section 时 no-op，避免对普通请求引入额外格式。
 */
public class ContextEnvelopeRenderAdvisor implements BaseAdvisor {

    public static final int DEFAULT_ORDER = -70;

    private final int order;

    public ContextEnvelopeRenderAdvisor() {
        this(DEFAULT_ORDER);
    }

    public ContextEnvelopeRenderAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (request == null || request.prompt() == null || request.context() == null) {
            return request;
        }
        Map<String, Object> ctx = request.context();
        String ltm = stringValue(ctx.get(ContextEnvelopeComposer.CTX_LTM));
        String episodic = stringValue(ctx.get(ContextEnvelopeComposer.CTX_EPISODIC));
        String runtime = stringValue(ctx.get(ContextEnvelopeComposer.CTX_RUNTIME));
        String outputContract = stringValue(ctx.get(ContextEnvelopeComposer.CTX_OUTPUT_CONTRACT));
        if (isBlank(ltm) && isBlank(episodic) && isBlank(runtime)) {
            return request;
        }

        Prompt originalPrompt = request.prompt();
        UserMessage userMessage = originalPrompt.getUserMessage();
        String task = userMessage != null ? userMessage.getText() : null;
        String rendered = ContextEnvelopeComposer.render(
                new ContextEnvelopeComposer.ContextEnvelopeInput(ltm, episodic, runtime, task, outputContract));
        if (rendered.isBlank()) {
            return request;
        }

        List<Message> originalMessages = originalPrompt.getInstructions();
        List<Message> messages = new ArrayList<>(originalMessages);
        int userIndex = lastUserMessageIndex(messages);
        if (userIndex >= 0) {
            messages.set(userIndex, new UserMessage(rendered));
        } else {
            messages.add(new UserMessage(rendered));
        }

        Map<String, Object> nextCtx = new LinkedHashMap<>(ctx);
        nextCtx.remove(ContextEnvelopeComposer.CTX_LTM);
        nextCtx.remove(ContextEnvelopeComposer.CTX_EPISODIC);
        nextCtx.remove(ContextEnvelopeComposer.CTX_RUNTIME);
        nextCtx.remove(ContextEnvelopeComposer.CTX_OUTPUT_CONTRACT);

        return ChatClientRequest.builder()
                // 必须透传原 Prompt options：per-request dynamic tool callbacks / ToolContext 都在这里。
                .prompt(Prompt.builder().messages(messages).chatOptions(originalPrompt.getOptions()).build())
                .context(nextCtx)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(before(request, chain));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(before(request, chain));
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private static int lastUserMessageIndex(List<Message> messages) {
        if (messages == null) return -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) return i;
        }
        return -1;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
