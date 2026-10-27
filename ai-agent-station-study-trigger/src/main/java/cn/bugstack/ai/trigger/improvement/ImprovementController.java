package cn.bugstack.ai.trigger.improvement;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Owner-scoped improvement suggestions: review a run, validate the proposed draft, then adopt or discard it. */
@RestController
@RequestMapping(value = "/api/v1/improvements", produces = "application/json")
public class ImprovementController {
    private final ImprovementService improvements;

    public ImprovementController(ImprovementService improvements) {
        this.improvements = improvements;
    }

    /** Body: {@code {"runId": ...}} or {@code {"evalRunId": ..., "resultId": ...}}. */
    @PostMapping public Object request(Authentication a, @RequestBody JsonNode body) { return ok(improvements.request(user(a), body)); }
    @GetMapping public Object list(Authentication a, @RequestParam(required = false) String runId) { return ok(improvements.list(user(a), runId)); }
    @GetMapping("/{id}") public Object view(Authentication a, @PathVariable String id) { return ok(improvements.view(user(a), id)); }
    @PostMapping("/{id}/validate") public Object validate(Authentication a, @PathVariable String id) { return ok(improvements.validate(user(a), id)); }
    @PostMapping("/{id}/adopt") public Object adopt(Authentication a, @PathVariable String id) { return ok(improvements.adopt(user(a), id)); }
    @PostMapping("/{id}/reject") public Object reject(Authentication a, @PathVariable String id) { return ok(improvements.reject(user(a), id)); }

    private static String user(Authentication a) {
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return a.getName();
    }

    private static Object ok(Object data) {
        return Map.of("code", "0000", "info", "成功", "data", data);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("code", String.valueOf(e.getStatusCode().value()),
                "info", e.getReason() == null ? "操作失败" : e.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "400", "info", e.getMessage() == null ? "参数无效" : e.getMessage()));
    }
}
