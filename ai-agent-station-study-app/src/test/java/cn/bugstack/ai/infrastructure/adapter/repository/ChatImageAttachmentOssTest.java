package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageInput;
import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import cn.bugstack.ai.infrastructure.dao.IAiChatAttachmentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatAttachment;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatImageAttachmentOssTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    public void newLocalImageWritesPayloadOnlyToOss() {
        CapturingDao dao = new CapturingDao();
        FakeStorage storage = new FakeStorage();
        ChatImageAttachmentService service = service(dao, storage);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);

        List<ChatImageRef> refs = service.prepareAndStore(
                "tenant:user:session",
                "user",
                "run-1",
                "describe",
                List.of(ChatImageInput.builder()
                        .sourceType("BASE64")
                        .dataUrl(dataUrl)
                        .name("pixel.png")
                        .build()));

        assertEquals(1, refs.size());
        assertEquals("https://signed.example/" + storage.putKeys.get(0), refs.get(0).getAccessUrl());
        assertEquals(1, dao.inserted.size());
        AiChatAttachment row = dao.inserted.get(0);
        assertNull(row.getImageData());
        assertEquals("OSS", row.getStorageProvider());
        assertEquals("tagent-img", row.getBucketName());
        assertEquals(storage.putKeys.get(0), row.getObjectKey());
        assertEquals(PNG.length, row.getFileSize().intValue());
        assertFalse(storage.putKeys.get(0).contains("pixel.png"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void remoteDownloaderRejectsLoopbackBeforeConnecting() {
        new RemoteImageDownloader().download("http://127.0.0.1/private.png", 1024);
    }

    @Test
    public void remoteDownloaderUsesHttpsProxyFromEnvironment() {
        HttpClient client = RemoteImageDownloader.buildClient(Map.of(
                "HTTPS_PROXY", "http://127.0.0.1:7897",
                "HTTP_PROXY", "http://127.0.0.1:7898"));

        List<Proxy> proxies = client.proxy().orElseThrow()
                .select(URI.create("https://raw.githubusercontent.com/example/image.png"));

        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertTrue(proxies.get(0).address() instanceof InetSocketAddress);
        InetSocketAddress address = (InetSocketAddress) proxies.get(0).address();
        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(7897, address.getPort());
    }

    @Test
    public void configuredImageProxyOverridesEnvironment() {
        HttpClient client = RemoteImageDownloader.buildClient(
                "http://127.0.0.1:7896",
                Map.of("HTTPS_PROXY", "http://127.0.0.1:7897"));

        InetSocketAddress address = (InetSocketAddress) client.proxy().orElseThrow()
                .select(URI.create("https://raw.githubusercontent.com/example/image.png"))
                .get(0)
                .address();

        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(7896, address.getPort());
    }

    private ChatImageAttachmentService service(CapturingDao dao, FakeStorage storage) {
        ChatImageAttachmentService service = new ChatImageAttachmentService();
        ReflectionTestUtils.setField(service, "attachmentDao", dao);
        ReflectionTestUtils.setField(service, "objectStorage", storage);
        ReflectionTestUtils.setField(service, "remoteImageDownloader", new RemoteImageDownloader());
        ReflectionTestUtils.setField(service, "maxImages", 4);
        ReflectionTestUtils.setField(service, "maxImageBytes", 10 * 1024 * 1024L);
        ReflectionTestUtils.setField(service, "objectPrefix", "chat-images");
        return service;
    }

    private static final class FakeStorage implements ChatImageObjectStorage {
        private final List<String> putKeys = new ArrayList<>();

        @Override
        public StoredObject put(String objectKey, byte[] data, String mimeType) {
            putKeys.add(objectKey);
            return new StoredObject(objectKey, "etag");
        }

        @Override
        public String createSignedGetUrl(String objectKey) {
            return "https://signed.example/" + objectKey;
        }

        @Override
        public void delete(String objectKey) {
        }

        @Override
        public String provider() {
            return "OSS";
        }

        @Override
        public String bucket() {
            return "tagent-img";
        }
    }

    private static final class CapturingDao implements IAiChatAttachmentDao {
        private final List<AiChatAttachment> inserted = new ArrayList<>();

        @Override
        public void insertBatch(List<AiChatAttachment> list) {
            inserted.addAll(list);
        }

        @Override
        public List<AiChatAttachment> findByAttachmentIds(List<String> attachmentIds) {
            return List.of();
        }

        @Override
        public AiChatAttachment findOwned(String attachmentId, String userId) {
            return null;
        }

        @Override
        public List<AiChatAttachment> findOwnedByConversation(String conversationId, String userId) {
            return List.of();
        }

        @Override
        public List<AiChatAttachment> findLegacyWithoutObjectKey() {
            return List.of();
        }

        @Override
        public int markStoredInOss(String attachmentId, String storageProvider,
                                  String bucketName, String objectKey, String etag) {
            return 0;
        }

        @Override
        public int deleteOwnedByConversation(String conversationId, String userId) {
            return 0;
        }
    }
}
