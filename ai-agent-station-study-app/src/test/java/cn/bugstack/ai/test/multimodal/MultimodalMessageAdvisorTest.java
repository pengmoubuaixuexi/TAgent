package cn.bugstack.ai.test.multimodal;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.service.multimodal.MultimodalMessageAdvisor;
import org.junit.Test;
import org.springframework.core.Ordered;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MultimodalMessageAdvisorTest {

    private static final ChatImageRef URL_IMAGE = ChatImageRef.builder()
            .attachmentId("image-1")
            .sourceType("URL")
            .sourceUrl("https://example.com/diagram.png")
            .mimeType("image/png")
            .name("diagram.png")
            .build();

    @Test
    public void imageCapableModelKeepsRealMedia() {
        MultimodalMessageAdvisor advisor = new MultimodalMessageAdvisor(true);
        ChatClientRequest rendered = advisor.before(request(List.of(URL_IMAGE)), null);

        UserMessage user = rendered.prompt().getUserMessage();
        assertEquals("介绍图片", user.getText());
        assertEquals(1, user.getMedia().size());
    }

    @Test
    public void textOnlyModelProjectsImageReferenceWithoutMedia() {
        MultimodalMessageAdvisor advisor = new MultimodalMessageAdvisor(false);
        ChatClientRequest rendered = advisor.before(request(List.of(URL_IMAGE)), null);

        UserMessage user = rendered.prompt().getUserMessage();
        assertTrue(user.getMedia().isEmpty());
        assertTrue(user.getText().contains("https://example.com/diagram.png"));
        assertTrue(user.getText().contains("本条用户消息包含图片"));
    }

    @Test
    public void capabilityJsonDefaultsToTextOnlyAndRecognizesImage() {
        assertFalse(AiClientModelVO.builder().build().supportsImageInput());
        assertFalse(AiClientModelVO.builder()
                .capabilitiesJson("{\"inputModalities\":[\"TEXT\"]}")
                .build()
                .supportsImageInput());
        assertTrue(AiClientModelVO.builder()
                .capabilitiesJson("{\"inputModalities\":[\"TEXT\",\"IMAGE\"]}")
                .build()
                .supportsImageInput());
    }

    @Test
    public void advisorRunsBeforeSpringAiTerminalModelAdvisor() {
        assertTrue(new MultimodalMessageAdvisor(true).getOrder() < Ordered.LOWEST_PRECEDENCE);
    }

    private ChatClientRequest request(List<ChatImageRef> images) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("介绍图片")))
                .context(Map.of(MultimodalMessageAdvisor.CURRENT_IMAGES_CONTEXT_KEY, images))
                .build();
    }
}
