package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import cn.bugstack.ai.domain.agent.service.multimodal.IChatImageAttachmentService;
import cn.bugstack.ai.infrastructure.dao.po.AiChatMemory;
import org.junit.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatMessagePartsCodecTest {

    @Test
    public void storesOnlyStableImageReferencesAndNeverBinaryData() {
        ChatImageRef image = ChatImageRef.builder()
                .attachmentId("attachment-1")
                .sourceType("BASE64")
                .mimeType("image/png")
                .name("local.png")
                .size(3L)
                .sha256("abc")
                .data(new byte[]{1, 2, 3})
                .build();

        String json = ChatMessagePartsCodec.encode("看看图片", List.of(image));

        assertFalse(json.contains("AQID"));
        assertFalse(json.contains("\"data\""));
        assertEquals(List.of("attachment-1"), ChatMessagePartsCodec.attachmentIds(json));
        assertEquals("local.png", ChatMessagePartsCodec.decodeImages(json).get(0).getName());
    }

    @Test
    public void restoresLocalImageBytesFromStableChatMemoryReference() throws Exception {
        ChatImageRef storedReference = ChatImageRef.builder()
                .attachmentId("attachment-2")
                .sourceType("BASE64")
                .mimeType("image/png")
                .name("history.png")
                .size(4L)
                .sha256("def")
                .build();
        String contentParts = ChatMessagePartsCodec.encode("继续分析这张图", List.of(storedReference));

        ChatImageRef loadedAttachment = ChatImageRef.builder()
                .attachmentId("attachment-2")
                .sourceType("BASE64")
                .mimeType("image/png")
                .name("history.png")
                .size(4L)
                .sha256("def")
                .data(new byte[]{10, 20, 30, 40})
                .build();

        MyBatisChatMemoryRepository repository = new MyBatisChatMemoryRepository();
        Field attachmentServiceField = MyBatisChatMemoryRepository.class
                .getDeclaredField("imageAttachmentService");
        attachmentServiceField.setAccessible(true);
        attachmentServiceField.set(repository, new StubAttachmentService(loadedAttachment));

        AiChatMemory row = AiChatMemory.builder()
                .messageType("USER")
                .content("继续分析这张图")
                .contentParts(contentParts)
                .mediaCount(1)
                .build();
        Method mapper = MyBatisChatMemoryRepository.class
                .getDeclaredMethod("toSpringMessage", AiChatMemory.class);
        mapper.setAccessible(true);
        Message restored = (Message) mapper.invoke(repository, row);

        assertTrue(restored instanceof UserMessage);
        UserMessage user = (UserMessage) restored;
        assertEquals("继续分析这张图", user.getText());
        assertEquals(1, user.getMedia().size());
        assertTrue(user.getMetadata().containsKey("chatImageRefs"));
        assertEquals(4, user.getMedia().get(0).getDataAsByteArray().length);
    }

    @Test
    public void stripsBrowserFragmentBeforeSendingRemoteImageToProvider() {
        assertEquals(
                "https://i-blog.csdnimg.cn/direct/example.png",
                ChatImageAttachmentService.normalizeHttpImageUrl(
                        "https://i-blog.csdnimg.cn/direct/example.png#pic_center"));
    }

    @Test
    public void convertsGithubBlobImageToRawUrl() {
        assertEquals(
                "https://raw.githubusercontent.com/pengmoubuaixuexi/TAgent/main/docs/images/architecture.png",
                ChatImageAttachmentService.normalizeHttpImageUrl(
                        "https://github.com/pengmoubuaixuexi/TAgent/blob/main/docs/images/architecture.png"));
    }

    private static final class StubAttachmentService implements IChatImageAttachmentService {

        private final ChatImageRef image;

        private StubAttachmentService(ChatImageRef image) {
            this.image = image;
        }

        @Override
        public List<ChatImageRef> prepareAndStore(String conversationId,
                                                  String userId,
                                                  String runId,
                                                  String message,
                                                  List<cn.bugstack.ai.domain.agent.model.entity.ChatImageInput> inputs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatImageRef> loadByAttachmentIds(List<String> attachmentIds) {
            return attachmentIds.contains(image.getAttachmentId()) ? List.of(image) : List.of();
        }

        @Override
        public ChatImageRef loadOwned(String attachmentId, String userId) {
            return image.getAttachmentId().equals(attachmentId) ? image : null;
        }

        @Override
        public int deleteOwnedByConversation(String conversationId, String userId) {
            return 0;
        }
    }
}
