package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.model.entity.ChatFileRef;
import cn.bugstack.ai.domain.agent.model.entity.ChatImageRef;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral ChatMemory content-parts codec.
 */
final class ChatMessagePartsCodec {

    private ChatMessagePartsCodec() {
    }

    static String encode(String text, List<ChatImageRef> images) {
        return encode(text, images, List.of());
    }

    /** File parts keep metadata only; extracted text is read from the attachment store when needed. */
    static String encode(String text, List<ChatImageRef> images, List<ChatFileRef> files) {
        if ((images == null || images.isEmpty()) && (files == null || files.isEmpty())) return null;
        JSONArray parts = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", text == null ? "" : text);
        parts.add(textPart);
        if (files != null) {
            for (ChatFileRef file : files) {
                if (file == null || file.getAttachmentId() == null) continue;
                JSONObject part = new JSONObject();
                part.put("type", "file");
                part.put("attachmentId", file.getAttachmentId());
                part.put("kind", file.getKind());
                part.put("name", file.getFileName());
                part.put("mimeType", file.getMimeType());
                part.put("fileKind", file.getFileKind());
                part.put("size", file.getSize());
                part.put("artifactId", file.getArtifactId());
                part.put("artifactRevision", file.getArtifactRevision());
                part.put("artifactFormat", file.getArtifactFormat());
                parts.add(part);
            }
        }
        for (ChatImageRef image : images == null ? List.<ChatImageRef>of() : images) {
            if (image == null || image.getAttachmentId() == null) continue;
            JSONObject part = new JSONObject();
            part.put("type", "image");
            part.put("attachmentId", image.getAttachmentId());
            part.put("sourceType", image.getSourceType());
            part.put("sourceUrl", image.getSourceUrl());
            part.put("mimeType", image.getMimeType());
            part.put("name", image.getName());
            part.put("size", image.getSize());
            part.put("sha256", image.getSha256());
            parts.add(part);
        }
        return parts.size() > 1 ? parts.toJSONString() : null;
    }

    static List<ChatImageRef> decodeImages(String contentParts) {
        if (contentParts == null || contentParts.isBlank()) return List.of();
        try {
            JSONArray parts = JSON.parseArray(contentParts);
            List<ChatImageRef> images = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part == null || !"image".equals(part.getString("type"))) continue;
                images.add(ChatImageRef.builder()
                        .attachmentId(part.getString("attachmentId"))
                        .sourceType(part.getString("sourceType"))
                        .sourceUrl(part.getString("sourceUrl"))
                        .mimeType(part.getString("mimeType"))
                        .name(part.getString("name"))
                        .size(part.getLong("size"))
                        .sha256(part.getString("sha256"))
                        .build());
            }
            return images;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static List<ChatFileRef> decodeFiles(String contentParts) {
        if (contentParts == null || contentParts.isBlank()) return List.of();
        try {
            JSONArray parts = JSON.parseArray(contentParts);
            List<ChatFileRef> files = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part == null || !"file".equals(part.getString("type"))) continue;
                files.add(ChatFileRef.builder()
                        .attachmentId(part.getString("attachmentId"))
                        .kind(part.getString("kind"))
                        .fileName(part.getString("name"))
                        .mimeType(part.getString("mimeType"))
                        .fileKind(part.getString("fileKind"))
                        .size(part.getLong("size"))
                        .artifactId(part.getString("artifactId"))
                        .artifactRevision(part.getInteger("artifactRevision"))
                        .artifactFormat(part.getString("artifactFormat"))
                        .build());
            }
            return files;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** Image attachment ids only; file parts are restored as text notes. */
    static List<String> attachmentIds(String contentParts) {
        return decodeImages(contentParts).stream()
                .map(ChatImageRef::getAttachmentId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }
}
