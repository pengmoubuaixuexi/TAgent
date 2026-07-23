package cn.bugstack.ai.domain.agent.service.multimodal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalizes provider-neutral multimodal messages at the OpenAI-compatible
 * wire boundary.
 */
public final class OpenAiMultimodalRequestNormalizer {

    private OpenAiMultimodalRequestNormalizer() {
    }

    /**
     * MiMo's documented OpenAI-compatible examples put every image part before
     * the text part. Spring AI currently serializes {@code UserMessage} as text
     * first and media afterwards. Keep all parts intact, but use MiMo's stable
     * ordering so the model treats the image as direct visual input instead of
     * merely an external URL mentioned in the prompt.
     *
     * @return whether the request map was changed
     */
    @SuppressWarnings("unchecked")
    public static boolean imagesBeforeText(Map<String, Object> requestMap) {
        if (requestMap == null) return false;
        Object messagesValue = requestMap.get("messages");
        if (!(messagesValue instanceof List<?> messages)) return false;

        boolean changed = false;
        for (Object messageValue : messages) {
            if (!(messageValue instanceof Map<?, ?> rawMessage)) continue;
            if (!"user".equals(rawMessage.get("role"))) continue;
            Object contentValue = rawMessage.get("content");
            if (!(contentValue instanceof List<?> content) || content.size() < 2) continue;

            List<Object> images = new ArrayList<>();
            List<Object> others = new ArrayList<>();
            boolean nonImageSeen = false;
            boolean imageAfterNonImage = false;
            for (Object part : content) {
                if (isImagePart(part)) {
                    if (nonImageSeen) imageAfterNonImage = true;
                    images.add(part);
                } else {
                    nonImageSeen = true;
                    others.add(part);
                }
            }
            if (images.isEmpty() || !imageAfterNonImage) continue;

            List<Object> normalized = new ArrayList<>(content.size());
            normalized.addAll(images);
            normalized.addAll(others);
            ((Map<String, Object>) rawMessage).put("content", normalized);
            changed = true;
        }
        return changed;
    }

    private static boolean isImagePart(Object part) {
        return part instanceof Map<?, ?> map && "image_url".equals(map.get("type"));
    }
}
