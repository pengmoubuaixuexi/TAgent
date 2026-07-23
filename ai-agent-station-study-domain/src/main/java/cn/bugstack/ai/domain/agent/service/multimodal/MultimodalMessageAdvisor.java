package cn.bugstack.ai.domain.agent.service.multimodal;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders provider-neutral image messages for one configured model.
 */
@Slf4j
public class MultimodalMessageAdvisor implements BaseAdvisor {

    public static final String CURRENT_IMAGES_CONTEXT_KEY = "chat_current_images";
    /**
     * Must run after the normal prompt/history advisors, but strictly before
     * Spring AI's terminal model advisor. LOWEST_PRECEDENCE can tie with the
     * terminal advisor and leave this advisor unreachable.
     */
    public static final int ORDER = 1_000;

    private final boolean imageInputSupported;

    public MultimodalMessageAdvisor(boolean imageInputSupported) {
        this.imageInputSupported = imageInputSupported;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        ChatClientRequest withCurrentImages = attachCurrentImages(request);
        return imageInputSupported ? withCurrentImages : projectToText(withCurrentImages);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest rendered = before(request, chain);
        try {
            return after(chain.nextCall(rendered), chain);
        } catch (RuntimeException error) {
            if (imageInputSupported && hasMedia(rendered) && isUnsupportedImageError(error)) {
                log.warn("configured model rejected image input; retrying once with text projection: {}",
                        safeMessage(error));
                CallAdvisorChain retryChain = freshCallTail(chain);
                if (retryChain != null) {
                    return after(retryChain.nextCall(projectToText(rendered)), retryChain);
                }
            }
            throw error;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest rendered = before(request, chain);
        Flux<ChatClientResponse> first = chain.nextStream(rendered);
        if (!imageInputSupported || !hasMedia(rendered)) return first;
        return first.onErrorResume(error -> {
            if (!isUnsupportedImageError(error)) return Flux.error(error);
            log.warn("configured model rejected image input; retrying stream once with text projection: {}",
                    safeMessage(error));
            StreamAdvisorChain retryChain = freshStreamTail(chain);
            return retryChain == null
                    ? Flux.error(error)
                    : retryChain.nextStream(projectToText(rendered));
        });
    }

    /**
     * Advisor chains are destructive deques: once the terminal model advisor
     * has been popped for the first attempt, calling nextCall/nextStream on the
     * same chain cannot retry. Build a fresh chain containing only advisors
     * after this renderer (normally the terminal model advisor).
     */
    private CallAdvisorChain freshCallTail(CallAdvisorChain chain) {
        if (!(chain instanceof BaseAdvisorChain base)) return null;
        List<CallAdvisor> tail = tailAfterSelf(base.getCallAdvisors());
        if (tail.isEmpty()) return null;
        List<Advisor> advisors = tail.stream().map(Advisor.class::cast).toList();
        return DefaultAroundAdvisorChain.builder(base.getObservationRegistry())
                .pushAll(advisors)
                .build();
    }

    private StreamAdvisorChain freshStreamTail(StreamAdvisorChain chain) {
        if (!(chain instanceof BaseAdvisorChain base)) return null;
        List<StreamAdvisor> tail = tailAfterSelf(base.getStreamAdvisors());
        if (tail.isEmpty()) return null;
        List<Advisor> advisors = tail.stream().map(Advisor.class::cast).toList();
        return DefaultAroundAdvisorChain.builder(base.getObservationRegistry())
                .pushAll(advisors)
                .build();
    }

    private <T extends Advisor> List<T> tailAfterSelf(List<T> advisors) {
        if (advisors == null || advisors.isEmpty()) return List.of();
        for (int i = 0; i < advisors.size(); i++) {
            if (advisors.get(i) == this) {
                return i + 1 < advisors.size()
                        ? List.copyOf(advisors.subList(i + 1, advisors.size()))
                        : List.of();
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private ChatClientRequest attachCurrentImages(ChatClientRequest request) {
        Object value = request.context().get(CURRENT_IMAGES_CONTEXT_KEY);
        if (!(value instanceof List<?> raw) || raw.isEmpty()) return request;
        List<ChatImageRef> refs = raw.stream()
                .filter(ChatImageRef.class::isInstance)
                .map(ChatImageRef.class::cast)
                .toList();
        if (refs.isEmpty()) return request;

        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        int userIndex = lastUserMessageIndex(messages);
        if (userIndex < 0) return request;
        UserMessage user = (UserMessage) messages.get(userIndex);
        List<Media> media = new ArrayList<>(user.getMedia());
        for (ChatImageRef ref : refs) {
            Media item = toMedia(ref);
            if (item != null) media.add(item);
        }
        if (media.size() == user.getMedia().size()) return request;
        Map<String, Object> metadata = new LinkedHashMap<>(user.getMetadata());
        metadata.put("chatImageRefs", refs);
        messages.set(userIndex, user.mutate().media(media).metadata(metadata).build());
        return rebuild(request, messages);
    }

    private ChatClientRequest projectToText(ChatClientRequest request) {
        List<Message> projected = new ArrayList<>(request.prompt().getInstructions().size());
        boolean changed = false;
        for (Message message : request.prompt().getInstructions()) {
            if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
                projected.add(user.mutate()
                        .text(projectedText(user))
                        .media(List.of())
                        .build());
                changed = true;
            } else {
                projected.add(message);
            }
        }
        return changed ? rebuild(request, projected) : request;
    }

    private String projectedText(UserMessage user) {
        StringBuilder text = new StringBuilder(user.getText() == null ? "" : user.getText());
        Object value = user.getMetadata().get("chatImageRefs");
        List<ChatImageRef> refs = value instanceof List<?> list
                ? list.stream().filter(ChatImageRef.class::isInstance).map(ChatImageRef.class::cast).toList()
                : List.of();
        text.append("\n\n[本条用户消息包含图片]");
        for (int i = 0; i < user.getMedia().size(); i++) {
            ChatImageRef ref = i < refs.size() ? refs.get(i) : null;
            String name = ref != null && ref.getName() != null ? ref.getName() : "图片 " + (i + 1);
            String usableUrl = ref == null ? null
                    : (ref.getAccessUrl() != null ? ref.getAccessUrl() : ref.getSourceUrl());
            if (usableUrl != null) {
                text.append("\n- ").append(name).append(": ").append(usableUrl);
            } else {
                text.append("\n- 本地图片附件: ").append(name);
            }
        }
        return text.toString();
    }

    private ChatClientRequest rebuild(ChatClientRequest request, List<Message> messages) {
        return ChatClientRequest.builder()
                .prompt(Prompt.builder()
                        .messages(messages)
                        .chatOptions(request.prompt().getOptions())
                        .build())
                .context(request.context())
                .build();
    }

    private Media toMedia(ChatImageRef ref) {
        if (ref == null) return null;
        MimeType mimeType;
        try {
            mimeType = MimeType.valueOf(ref.getMimeType() == null ? "image/jpeg" : ref.getMimeType());
        } catch (Exception ignored) {
            mimeType = MimeType.valueOf("image/jpeg");
        }
        try {
            if (ref.getAccessUrl() != null) {
                return new Media(mimeType, URI.create(ref.getAccessUrl()));
            }
            if ("URL".equalsIgnoreCase(ref.getSourceType()) && ref.getSourceUrl() != null) {
                return new Media(mimeType, URI.create(ref.getSourceUrl()));
            }
            if (ref.getData() != null && ref.getData().length > 0) {
                return new Media(mimeType, new ByteArrayResource(ref.getData()) {
                    @Override
                    public String getFilename() {
                        return ref.getName() == null ? "image" : ref.getName();
                    }
                });
            }
        } catch (Exception e) {
            log.warn("skip invalid image attachmentId={}: {}", ref.getAttachmentId(), e.getMessage());
        }
        return null;
    }

    private int lastUserMessageIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) return i;
        }
        return -1;
    }

    private boolean hasMedia(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .anyMatch(message -> message instanceof UserMessage user && !user.getMedia().isEmpty());
    }

    private boolean isUnsupportedImageError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if ((lower.contains("image") || lower.contains("vision") || lower.contains("multimodal"))
                        && (lower.contains("not support") || lower.contains("unsupported")
                        || lower.contains("no endpoints") || lower.contains("invalid"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) return error == null ? "unknown" : error.getClass().getSimpleName();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }
}
