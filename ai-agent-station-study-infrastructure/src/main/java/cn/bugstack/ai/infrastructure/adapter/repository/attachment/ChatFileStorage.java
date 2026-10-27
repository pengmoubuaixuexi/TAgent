package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Original attachment files: private OSS when configured, otherwise the application's data directory. */
public interface ChatFileStorage {

    String provider();

    String bucket();

    void put(String objectKey, Path file, String mimeType) throws IOException;

    void download(String objectKey, Path target) throws IOException;

    /** Streams the original for a download response (local storage). */
    InputStream open(String objectKey) throws IOException;

    /** Streams the stored bytes from any provider. The caller closes the stream. */
    InputStream read(String objectKey) throws IOException;

    /** A short-lived URL that downloads the file as an attachment, or null when not supported. */
    String signedDownloadUrl(String objectKey, String fileName);

    void delete(String objectKey) throws IOException;
}
