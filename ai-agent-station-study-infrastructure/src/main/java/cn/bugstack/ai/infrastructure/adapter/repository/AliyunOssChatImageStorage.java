package cn.bugstack.ai.infrastructure.adapter.repository;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectResult;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@ConditionalOnProperty(
        name = "agent.multimodal.oss.enabled",
        havingValue = "true")
class AliyunOssChatImageStorage implements ChatImageObjectStorage {

    private final OSSClient client;
    private final String bucket;
    private final Duration signedUrlTtl;

    AliyunOssChatImageStorage(
            @Value("${agent.multimodal.oss.region:cn-beijing}") String region,
            @Value("${agent.multimodal.oss.endpoint:https://oss-cn-beijing.aliyuncs.com}") String endpoint,
            @Value("${agent.multimodal.oss.bucket:}") String bucket,
            @Value("${agent.multimodal.oss.signed-url-ttl-seconds:1800}") long signedUrlTtlSeconds) {
        this.bucket = requireText(bucket, "OSS bucket");
        this.signedUrlTtl = Duration.ofSeconds(Math.max(60, signedUrlTtlSeconds));
        this.client = OSSClient.newBuilder()
                .region(requireText(region, "OSS region"))
                .endpoint(requireText(endpoint, "OSS endpoint"))
                .credentialsProvider(new EnvironmentVariableCredentialsProvider())
                .connectTimeout(Duration.ofSeconds(10))
                .readWriteTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public StoredObject put(String objectKey, byte[] data, String mimeType) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("OSS image payload is empty");
        }
        PutObjectResult result = client.putObject(PutObjectRequest.newBuilder()
                .bucket(bucket)
                .key(requireText(objectKey, "OSS object key"))
                .contentType(requireText(mimeType, "image MIME type"))
                .contentLength(data.length)
                .body(BinaryData.fromBytes(data))
                .build());
        return new StoredObject(objectKey, result.eTag());
    }

    @Override
    public String createSignedGetUrl(String objectKey) {
        PresignResult result = client.presign(
                GetObjectRequest.newBuilder()
                        .bucket(bucket)
                        .key(requireText(objectKey, "OSS object key"))
                        .build(),
                PresignOptions.newBuilder()
                        .expiration(signedUrlTtl)
                        .build());
        return result.url();
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        client.deleteObject(DeleteObjectRequest.newBuilder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }

    @Override
    public String provider() {
        return "OSS";
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @PreDestroy
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
