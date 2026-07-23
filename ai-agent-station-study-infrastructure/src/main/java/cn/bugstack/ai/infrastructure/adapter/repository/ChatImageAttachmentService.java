package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ChatImageInput;
import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import cn.bugstack.ai.domain.agent.service.multimodal.IChatImageAttachmentService;
import cn.bugstack.ai.infrastructure.dao.IAiChatAttachmentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiChatAttachment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ChatImageAttachmentService implements IChatImageAttachmentService {

    private static final Pattern DATA_URL = Pattern.compile(
            "^data:(image/[a-zA-Z0-9.+-]+);base64,([a-zA-Z0-9+/=\\r\\n]+)$");
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://[^\\s)\\]>\"']+?\\.(?:png|jpe?g|gif|webp|bmp)(?:[?#][^\\s)\\]>\"']*)?",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private IAiChatAttachmentDao attachmentDao;

    @Resource
    private ChatImageObjectStorage objectStorage;

    @Resource
    private RemoteImageDownloader remoteImageDownloader;

    @Value("${agent.multimodal.max-images:4}")
    private int maxImages;

    @Value("${agent.multimodal.max-image-bytes:10485760}")
    private long maxImageBytes;

    @Value("${agent.multimodal.oss.object-prefix:chat-images}")
    private String objectPrefix;

    @Override
    public List<ChatImageRef> prepareAndStore(String conversationId,
                                              String userId,
                                              String runId,
                                              String message,
                                              List<ChatImageInput> inputs) {
        Map<String, ChatImageInput> unique = new LinkedHashMap<>();
        if (inputs != null) {
            for (ChatImageInput input : inputs) {
                if (input == null) continue;
                String key = inputKey(input);
                if (key != null) unique.putIfAbsent(key, input);
            }
        }
        if (StringUtils.hasText(message)) {
            Matcher matcher = IMAGE_URL.matcher(message);
            while (matcher.find()) {
                String url = normalizeHttpImageUrl(matcher.group());
                unique.putIfAbsent("URL:" + url, ChatImageInput.builder()
                        .sourceType("URL")
                        .url(url)
                        .name(fileNameFromUrl(url))
                        .mimeType(inferMimeType(url))
                        .build());
            }
        }
        if (unique.isEmpty()) return List.of();
        if (unique.size() > maxImages) {
            throw new IllegalArgumentException("每条消息最多包含 " + maxImages + " 张图片");
        }

        List<AiChatAttachment> rows = new ArrayList<>(unique.size());
        List<ChatImageRef> refs = new ArrayList<>(unique.size());
        List<String> uploadedKeys = new ArrayList<>(unique.size());
        LocalDateTime now = LocalDateTime.now();
        try {
            for (ChatImageInput input : unique.values()) {
                String attachmentId = UUID.randomUUID().toString();
                PreparedImage prepared = prepareImage(input);
                String objectKey = buildObjectKey(now, attachmentId, prepared.mimeType());
                ChatImageObjectStorage.StoredObject stored =
                        objectStorage.put(objectKey, prepared.data(), prepared.mimeType());
                uploadedKeys.add(stored.objectKey());

                rows.add(AiChatAttachment.builder()
                        .attachmentId(attachmentId)
                        .conversationId(conversationId)
                        .userId(userId)
                        .runId(runId)
                        .sourceType(prepared.sourceType())
                        .sourceUrl(prepared.sourceUrl())
                        .imageData(null)
                        .storageProvider(objectStorage.provider())
                        .bucketName(objectStorage.bucket())
                        .objectKey(stored.objectKey())
                        .etag(stored.etag())
                        .mimeType(prepared.mimeType())
                        .originalName(prepared.name())
                        .fileSize((long) prepared.data().length)
                        .sha256(sha256(prepared.data()))
                        .createdAt(now)
                        .build());
                refs.add(ChatImageRef.builder()
                        .attachmentId(attachmentId)
                        .sourceType(prepared.sourceType())
                        .sourceUrl(prepared.sourceUrl())
                        .accessUrl(objectStorage.createSignedGetUrl(stored.objectKey()))
                        .mimeType(prepared.mimeType())
                        .name(prepared.name())
                        .size((long) prepared.data().length)
                        .sha256(sha256(prepared.data()))
                        .build());
            }
            attachmentDao.insertBatch(rows);
            return refs;
        } catch (RuntimeException error) {
            for (String key : uploadedKeys) {
                try {
                    objectStorage.delete(key);
                } catch (RuntimeException cleanupError) {
                    log.warn("cleanup orphan OSS image failed objectKey={}: {}",
                            key, cleanupError.getMessage());
                }
            }
            throw error;
        }
    }

    @Override
    public List<ChatImageRef> loadByAttachmentIds(List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
        List<AiChatAttachment> rows = attachmentDao.findByAttachmentIds(attachmentIds);
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, AiChatAttachment> byId = new LinkedHashMap<>();
        for (AiChatAttachment row : rows) byId.put(row.getAttachmentId(), row);
        List<ChatImageRef> ordered = new ArrayList<>();
        for (String id : attachmentIds) {
            AiChatAttachment row = byId.get(id);
            if (row != null) ordered.add(toRef(row));
        }
        return ordered;
    }

    @Override
    public ChatImageRef loadOwned(String attachmentId, String userId) {
        if (!StringUtils.hasText(attachmentId) || !StringUtils.hasText(userId)) return null;
        AiChatAttachment row = attachmentDao.findOwned(attachmentId, userId);
        return row == null ? null : toRef(row);
    }

    @Override
    public int deleteOwnedByConversation(String conversationId, String userId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(userId)) return 0;
        List<AiChatAttachment> rows = attachmentDao.findOwnedByConversation(conversationId, userId);
        int deleted = attachmentDao.deleteOwnedByConversation(conversationId, userId);
        if (rows != null) {
            for (AiChatAttachment row : rows) {
                if (!StringUtils.hasText(row.getObjectKey())) continue;
                try {
                    objectStorage.delete(row.getObjectKey());
                } catch (RuntimeException error) {
                    log.warn("delete OSS image failed attachmentId={} objectKey={}: {}",
                            row.getAttachmentId(), row.getObjectKey(), error.getMessage());
                }
            }
        }
        return deleted;
    }

    public int migrateLegacyToOss() {
        List<AiChatAttachment> rows = attachmentDao.findLegacyWithoutObjectKey();
        if (rows == null || rows.isEmpty()) return 0;
        int migrated = 0;
        for (AiChatAttachment row : rows) {
            byte[] data = row.getImageData();
            String mimeType = row.getMimeType();
            if ((data == null || data.length == 0) && StringUtils.hasText(row.getSourceUrl())) {
                RemoteImageDownloader.DownloadedImage downloaded =
                        remoteImageDownloader.download(row.getSourceUrl(), maxImageBytes);
                data = downloaded.data();
                mimeType = downloaded.mimeType();
            }
            if (data == null || data.length == 0) {
                log.warn("skip legacy image without payload attachmentId={}", row.getAttachmentId());
                continue;
            }
            LocalDateTime createdAt = row.getCreatedAt() == null ? LocalDateTime.now() : row.getCreatedAt();
            String objectKey = buildObjectKey(createdAt, row.getAttachmentId(), mimeType);
            ChatImageObjectStorage.StoredObject stored = objectStorage.put(objectKey, data, mimeType);
            try {
                int updated = attachmentDao.markStoredInOss(
                        row.getAttachmentId(),
                        objectStorage.provider(),
                        objectStorage.bucket(),
                        stored.objectKey(),
                        stored.etag());
                if (updated != 1) {
                    objectStorage.delete(stored.objectKey());
                    throw new IllegalStateException(
                            "legacy attachment metadata update failed: " + row.getAttachmentId());
                }
                migrated++;
            } catch (RuntimeException error) {
                try {
                    objectStorage.delete(stored.objectKey());
                } catch (RuntimeException cleanupError) {
                    log.warn("cleanup failed legacy OSS objectKey={}: {}",
                            stored.objectKey(), cleanupError.getMessage());
                }
                throw error;
            }
        }
        return migrated;
    }

    private PreparedImage prepareImage(ChatImageInput input) {
        String type = normalizeType(input);
        if ("URL".equals(type)) {
            String url = requireHttpUrl(input.getUrl());
            RemoteImageDownloader.DownloadedImage downloaded =
                    remoteImageDownloader.download(url, maxImageBytes);
            String name = StringUtils.hasText(input.getName()) ? input.getName() : fileNameFromUrl(url);
            return new PreparedImage("URL", url, downloaded.mimeType(), name, downloaded.data());
        }

        if (!StringUtils.hasText(input.getDataUrl())) {
            throw new IllegalArgumentException("BASE64 图片缺少 dataUrl");
        }
        Matcher matcher = DATA_URL.matcher(input.getDataUrl().trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("本地图片必须使用 data:image/...;base64,... 格式");
        }
        String mimeType = matcher.group(1).toLowerCase(Locale.ROOT);
        String encoded = matcher.group(2).replace("\r", "").replace("\n", "");
        long estimated = (encoded.length() * 3L) / 4L;
        if (estimated > maxImageBytes + 3) {
            throw new IllegalArgumentException("单张图片不能超过 " + maxImageBytes + " 字节");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片 Base64 内容无效", e);
        }
        if (data.length == 0 || data.length > maxImageBytes) {
            throw new IllegalArgumentException("图片为空或超过大小限制");
        }
        return new PreparedImage(
                "BASE64",
                null,
                mimeType,
                StringUtils.hasText(input.getName()) ? input.getName() : "image",
                data);
    }

    private String normalizeType(ChatImageInput input) {
        if (StringUtils.hasText(input.getSourceType())) {
            String normalized = input.getSourceType().trim().toUpperCase(Locale.ROOT);
            if ("URL".equals(normalized) || "BASE64".equals(normalized)) return normalized;
            throw new IllegalArgumentException("不支持的图片来源类型: " + input.getSourceType());
        }
        return StringUtils.hasText(input.getDataUrl()) ? "BASE64" : "URL";
    }

    private String inputKey(ChatImageInput input) {
        String type = normalizeType(input);
        if ("URL".equals(type) && StringUtils.hasText(input.getUrl())) {
            return "URL:" + normalizeHttpImageUrl(input.getUrl());
        }
        if ("BASE64".equals(type) && StringUtils.hasText(input.getDataUrl())) {
            return "BASE64:" + sha256(input.getDataUrl().getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    private String requireHttpUrl(String value) {
        return normalizeHttpImageUrl(value);
    }

    static String normalizeHttpImageUrl(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("URL 图片缺少 url");
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("图片 URL 仅支持 http/https");
            }
            // URI fragments are browser-side anchors and are never part of the
            // HTTP resource. Some OpenAI-compatible vision gateways reject or
            // silently ignore image_url values that retain "#pic_center".
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null)
                    .toASCIIString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片 URL 无效: " + value, e);
        } catch (Exception e) {
            throw new IllegalArgumentException("图片 URL 无效: " + value, e);
        }
    }

    private ChatImageRef toRef(AiChatAttachment row) {
        String accessUrl = null;
        if (StringUtils.hasText(row.getObjectKey())) {
            accessUrl = objectStorage.createSignedGetUrl(row.getObjectKey());
        }
        return ChatImageRef.builder()
                .attachmentId(row.getAttachmentId())
                .sourceType(row.getSourceType())
                .sourceUrl(row.getSourceUrl())
                .accessUrl(accessUrl)
                .data(row.getImageData())
                .mimeType(row.getMimeType())
                .name(row.getOriginalName())
                .size(row.getFileSize())
                .sha256(row.getSha256())
                .build();
    }

    private String buildObjectKey(LocalDateTime now, String attachmentId, String mimeType) {
        String prefix = StringUtils.hasText(objectPrefix)
                ? objectPrefix.replaceAll("^/+|/+$", "")
                : "chat-images";
        String date = now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return prefix + "/" + date + "/" + attachmentId + extensionFor(mimeType);
    }

    private String extensionFor(String mimeType) {
        if (mimeType == null) return ".jpg";
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            default -> ".jpg";
        };
    }

    private String normalizeMimeType(String mimeType, String url) {
        if (StringUtils.hasText(mimeType) && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return mimeType.toLowerCase(Locale.ROOT);
        }
        return inferMimeType(url);
    }

    private String inferMimeType(String value) {
        if (value == null) return "image/jpeg";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    private String fileNameFromUrl(String value) {
        try {
            String path = URI.create(value).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : "remote-image";
        } catch (Exception ignored) {
            return "remote-image";
        }
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record PreparedImage(
            String sourceType,
            String sourceUrl,
            String mimeType,
            String name,
            byte[] data) {
    }
}
