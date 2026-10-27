package cn.bugstack.ai.domain.agent.service.model.failover;

import java.util.List;
import java.util.Map;

/** What a substitute must support to serve this exact request. */
public record ModelRequestTraits(boolean tools, boolean images, boolean streaming) {
    static ModelRequestTraits of(Map<String, Object> body) {
        return new ModelRequestTraits(body.get("tools") instanceof List<?> tools && !tools.isEmpty(),
                containsImage(body.get("messages")), Boolean.TRUE.equals(body.get("stream")));
    }

    private static boolean containsImage(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("image_url".equals(map.get("type")) || "input_image".equals(map.get("type"))) return true;
            for (Object child : map.values()) if (containsImage(child)) return true;
        } else if (value instanceof List<?> list) {
            for (Object child : list) if (containsImage(child)) return true;
        }
        return false;
    }
}
