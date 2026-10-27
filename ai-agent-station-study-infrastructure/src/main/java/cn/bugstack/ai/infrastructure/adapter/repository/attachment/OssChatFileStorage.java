package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Private OSS bucket shared with chat images (agent.multimodal.oss.*), under its own key prefix. */
final class OssChatFileStorage implements ChatFileStorage, AutoCloseable {

    private final OSSClient client;
    private final String bucket;
    private final Duration signedUrlTtl;

    OssChatFileStorage(String region, String endpoint, String bucket, long signedUrlTtlSeconds) {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("OSS bucket is required");
        this.bucket = bucket.trim();
        this.signedUrlTtl = Duration.ofSeconds(Math.max(60, Math.min(signedUrlTtlSeconds, 3600)));
        this.client = OSSClient.newBuilder()
                .region(region)
                .endpoint(endpoint)
                .credentialsProvider(new EnvironmentVariableCredentialsProvider())
                .connectTimeout(Duration.ofSeconds(10))
                .readWriteTimeout(Duration.ofSeconds(120))
                .build();
    }

    @Override public String provider() { return "OSS"; }

    @Override public String bucket() { return bucket; }

    @Override
    public void put(String objectKey, Path file, String mimeType) throws IOException {
        long length = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            client.putObject(PutObjectRequest.newBuilder()
                    .bucket(bucket)
                    .key(objectKey)
                    // Never served inline from the bucket.
                    .contentType("application/octet-stream")
                    .contentLength(Math.toIntExact(length))
                    .body(BinaryData.fromStream(in, length))
                    .build());
        }
    }

    @Override
    public void download(String objectKey, Path target) throws IOException {
        try (GetObjectResult result = client.getObject(GetObjectRequest.newBuilder().bucket(bucket).key(objectKey).build());
             InputStream in = result.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("OSS download failed", e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        throw new UnsupportedOperationException("OSS downloads use signed URLs");
    }

    @Override
    public InputStream read(String objectKey) throws IOException {
        GetObjectResult result = client.getObject(GetObjectRequest.newBuilder().bucket(bucket).key(objectKey).build());
        InputStream body = result.body();
        return new java.io.FilterInputStream(body) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    try { result.close(); } catch (Exception ignored) { }
                }
            }
        };
    }

    @Override
    public String signedDownloadUrl(String objectKey, String fileName) {
        String encoded = URLEncoder.encode(fileName == null ? "attachment" : fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String disposition = "attachment; filename*=UTF-8" + "''" + encoded;
        return client.presign(GetObjectRequest.newBuilder()
                        .bucket(bucket)
                        .key(objectKey)
                        .responseContentDisposition(disposition)
                        .responseContentType("application/octet-stream")
                        .build(),
                PresignOptions.newBuilder().expiration(signedUrlTtl).build()).url();
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.newBuilder().bucket(bucket).key(objectKey).build());
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception ignored) { }
    }
}
