package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.infrastructure.adapter.repository.attachment.ChatFileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Chat attachments. An upload stays PENDING (visible only to its owner) until a message
 * that includes it is sent; then it follows that conversation.
 */
@RestController
@RequestMapping("/api/v1/agent/files")
public class ChatFileController {

    private final ChatFileService files;

    public ChatFileController(ChatFileService files) {
        this.files = files;
    }

    @PostMapping
    public Map<String, Object> upload(Authentication auth, @RequestParam("file") MultipartFile file) {
        return ok(files.upload(user(auth), file));
    }

    @GetMapping("/{attachmentId}")
    public Map<String, Object> status(Authentication auth, @PathVariable String attachmentId) {
        return ok(files.status(user(auth), attachmentId));
    }

    @DeleteMapping("/{attachmentId}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String attachmentId) {
        files.deletePending(user(auth), attachmentId);
        return ok(true);
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<?> download(Authentication auth, @PathVariable String attachmentId) {
        ChatFileService.Download file = files.download(user(auth), attachmentId);
        if (file.redirectUrl() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(file.redirectUrl()))
                    .header("Cache-Control", "no-store").build();
        }
        return ResponseEntity.ok()
                // User uploads are never rendered by this origin.
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store")
                .contentLength(file.size())
                .body(new InputStreamResource(file.stream()));
    }

    @ExceptionHandler(ChatFileService.Rejected.class)
    public ResponseEntity<Map<String, Object>> rejected(ChatFileService.Rejected e) {
        return ResponseEntity.badRequest().body(Map.of("code", "400", "info", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("code", "413", "info", "单个文件不能超过 50 MB"));
    }

    private static String user(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ChatFileService.Rejected("请先登录");
        }
        return auth.getName();
    }

    private static Map<String, Object> ok(Object data) {
        return Map.of("code", "0000", "info", "成功", "data", data);
    }
}
