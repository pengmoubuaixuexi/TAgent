package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.infrastructure.adapter.repository.attachment.ChatFileService;
import cn.bugstack.ai.infrastructure.adapter.repository.attachment.ChatFileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Binary contents of FILE artifacts (sandbox outputs), in the same storage as chat attachments. */
@Component
public class ArtifactFileStore {

    public record Stored(String key, String sha256, long size) { }

    public static final long MAX_BYTES = 20L * 1024 * 1024;

    private final ChatFileStorage storage;
    private final Path tmpRoot;

    public ArtifactFileStore(ChatFileStorage storage,
                             @Value("${agent.attachments.cache-dir:./data/chat-files-cache}") String cacheDir) {
        this.storage = storage;
        this.tmpRoot = Path.of(cacheDir).toAbsolutePath().normalize().resolve("tmp");
    }

    public Stored put(String user, byte[] bytes, String mimeType) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("file is empty or too large");
        String sha = sha256(bytes);
        String key = "artifact-files/" + ChatFileService.ownerKey(user) + "/" + sha;
        Files.createDirectories(tmpRoot);
        Path temp = tmpRoot.resolve("artifact-" + UUID.randomUUID());
        try {
            Files.write(temp, bytes);
            storage.put(key, temp, mimeType);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new Stored(key, sha, bytes.length);
    }

    public byte[] read(String key) throws IOException {
        try (InputStream in = storage.read(key)) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("stored file is larger than allowed");
            return bytes;
        }
    }

    public void delete(String key) throws IOException {
        storage.delete(key);
    }

    /** A temporary local copy for previews; the caller deletes it. */
    public Path copy(String key) throws IOException {
        Files.createDirectories(tmpRoot);
        Path temp = tmpRoot.resolve("preview-src-" + UUID.randomUUID());
        Files.write(temp, read(key));
        return temp;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
