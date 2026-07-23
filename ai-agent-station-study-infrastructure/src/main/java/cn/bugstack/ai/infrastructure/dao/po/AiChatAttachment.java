package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiChatAttachment {

    private Long id;
    private String attachmentId;
    private String conversationId;
    private String userId;
    private String runId;
    private String sourceType;
    private String sourceUrl;
    private byte[] imageData;
    private String storageProvider;
    private String bucketName;
    private String objectKey;
    private String etag;
    private String mimeType;
    private String originalName;
    private Long fileSize;
    private String sha256;
    private LocalDateTime createdAt;
}
