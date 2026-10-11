package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Private, bounded source storage. All paths are generated UUIDs, never uploaded filenames. */
@Component
public class RagUploadStorage {
    private final Path root;
    private final long maximumBytes;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf", "doc", "docx", "ppt", "pptx", "csv", "json", "html", "htm");

    public RagUploadStorage(@Value("${agent.rag.ingestion-dir:./data/rag-ingestion}") String root,
                            @Value("${agent.rag.max-upload-bytes:20971520}") long maximumBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maximumBytes = Math.max(1, Math.min(maximumBytes, 100L * 1024 * 1024));
    }

    public record Stored(String path, String hash, long bytes) { }

    public Stored save(String taskId, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > maximumBytes) throw new RagStorageException("文件为空或超过上传大小限制。");
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw new RagStorageException("文件名无效，请重命名后上传。");
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            throw new RagStorageException("暂不支持这种文件格式，请使用 PDF、Office 文档或文本文件。");
        }
        Path target = resolve(taskId + "/source.bin", true);
        Path temporary = target.resolveSibling("source.pending");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1;) {
                    total += read;
                    if (total > maximumBytes) throw new RagStorageException("文件超过上传大小限制。");
                    digest.update(buffer, 0, read); out.write(buffer, 0, read);
                }
            }
            if (total == 0) throw new RagStorageException("文件内容为空。");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new Stored(taskId + "/source.bin", HexFormat.of().formatHex(digest.digest()), total);
        } catch (Exception error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            if (error instanceof RagStorageException safe) throw safe;
            throw new RagStorageException("无法保存上传原件，请检查应用数据目录后重试。", error);
        }
    }

    public MultipartFile open(RagIngestionTask task) {
        Path file = resolve(task.sourcePath(), false);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || task.fileSize() < 1
                    || task.fileSize() > maximumBytes || Files.size(file) != task.fileSize()) {
                throw new IOException("Source missing or size mismatch");
            }
            byte[] bytes = Files.readAllBytes(file);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(task.fileHash())) throw new IOException("Source checksum mismatch");
            return new SourceFile(task.fileName(), bytes);
        } catch (Exception error) {
            throw new RagStorageException("上传原件丢失或校验失败，请删除此任务后重新上传。", error);
        }
    }

    public void delete(String relative) {
        Path file = resolve(relative, false);
        try { Files.deleteIfExists(file); Files.deleteIfExists(file.getParent()); }
        catch (IOException error) { throw new RagStorageException("索引已清理，上传原件清理失败；请重试删除。", error); }
    }

    private Path resolve(String relative, boolean create) {
        if (relative == null || !relative.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/source\\.bin")) {
            throw new RagStorageException("上传原件位置无效，未访问任何文件。");
        }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new RagStorageException("上传原件位置无效。");
        try {
            if (create) Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path)) throw new IOException("Symlink rejected");
            if (create) Files.createDirectories(path.getParent());
            return path;
        } catch (IOException error) { throw new RagStorageException("上传原件目录不可用。", error); }
    }

    private record SourceFile(String filename, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File target) throws IOException { Files.write(target.toPath(), bytes); }
    }
}
