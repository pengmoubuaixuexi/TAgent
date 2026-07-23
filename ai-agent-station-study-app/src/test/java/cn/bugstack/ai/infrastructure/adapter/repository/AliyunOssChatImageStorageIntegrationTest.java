package cn.bugstack.ai.infrastructure.adapter.repository;

import org.junit.Assume;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

public class AliyunOssChatImageStorageIntegrationTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    public void uploadsSignsReadsAndDeletesPrivateObject() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("oss.integration"));
        Assume.assumeTrue(System.getenv("OSS_ACCESS_KEY_ID") != null);
        Assume.assumeTrue(System.getenv("OSS_ACCESS_KEY_SECRET") != null);

        AliyunOssChatImageStorage storage = new AliyunOssChatImageStorage(
                "cn-beijing",
                "https://oss-cn-beijing.aliyuncs.com",
                "tagent-img",
                300);
        String key = "chat-images/integration-tests/" + UUID.randomUUID() + ".png";
        try {
            ChatImageObjectStorage.StoredObject stored = storage.put(key, PNG, "image/png");
            assertFalse(stored.etag() == null || stored.etag().isBlank());
            String signedUrl = storage.createSignedGetUrl(key);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(signedUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(PNG, response.body());
        } finally {
            storage.delete(key);
            storage.close();
        }
    }
}
