package cn.bugstack.ai.infrastructure.adapter.repository;

interface ChatImageObjectStorage {

    StoredObject put(String objectKey, byte[] data, String mimeType);

    String createSignedGetUrl(String objectKey);

    void delete(String objectKey);

    String provider();

    String bucket();

    record StoredObject(String objectKey, String etag) {
    }
}
