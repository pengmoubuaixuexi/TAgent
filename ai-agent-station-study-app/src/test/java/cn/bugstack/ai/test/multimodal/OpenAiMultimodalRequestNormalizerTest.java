package cn.bugstack.ai.test.multimodal;

import cn.bugstack.ai.domain.agent.service.multimodal.OpenAiMultimodalRequestNormalizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiMultimodalRequestNormalizerTest {

    @Test
    public void movesImagesBeforeTextAndPreservesRelativeOrder() {
        Map<String, Object> text = text("describe the image");
        Map<String, Object> imageOne = image("https://example.com/one.png");
        Map<String, Object> imageTwo = image("data:image/png;base64,AAAA");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", new ArrayList<>(List.of(text, imageOne, imageTwo)));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", new ArrayList<>(List.of(user)));

        assertTrue(OpenAiMultimodalRequestNormalizer.imagesBeforeText(request));

        List<?> content = (List<?>) user.get("content");
        assertEquals(imageOne, content.get(0));
        assertEquals(imageTwo, content.get(1));
        assertEquals(text, content.get(2));
        assertFalse(OpenAiMultimodalRequestNormalizer.imagesBeforeText(request));
    }

    @Test
    public void leavesTextOnlyMessagesUnchanged() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", new ArrayList<>(List.of(text("hello"))));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messages", new ArrayList<>(List.of(user)));

        assertFalse(OpenAiMultimodalRequestNormalizer.imagesBeforeText(request));
    }

    private static Map<String, Object> text(String value) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", value);
        return part;
    }

    private static Map<String, Object> image(String url) {
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", url);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "image_url");
        part.put("image_url", imageUrl);
        return part;
    }
}
