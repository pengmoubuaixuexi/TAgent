-- V057: store chat image payloads in private Aliyun OSS.
-- New writes keep image_data NULL. Existing payloads remain temporarily until
-- the application migration uploads each object and clears its blob.

ALTER TABLE ai_chat_attachment
    ADD COLUMN storage_provider VARCHAR(16) NULL
        COMMENT 'OSS for externally stored payloads'
        AFTER image_data,
    ADD COLUMN bucket_name VARCHAR(128) NULL
        COMMENT 'OSS bucket containing the image'
        AFTER storage_provider,
    ADD COLUMN object_key VARCHAR(1024) NULL
        COMMENT 'Permanent OSS object key; never a signed URL'
        AFTER bucket_name,
    ADD COLUMN etag VARCHAR(128) NULL
        COMMENT 'OSS object ETag returned after upload'
        AFTER object_key,
    ADD KEY idx_chat_attachment_object_key (object_key(191));
