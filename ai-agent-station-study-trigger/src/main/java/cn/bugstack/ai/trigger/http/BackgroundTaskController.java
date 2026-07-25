package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.trigger.background.BackgroundTaskScheduler;
import cn.bugstack.ai.trigger.background.BackgroundTaskService;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent/background-tasks")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class BackgroundTaskController {

    private final BackgroundTaskService service;
    private final BackgroundTaskScheduler scheduler;

    public BackgroundTaskController(BackgroundTaskService service, BackgroundTaskScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @PostMapping("/interpret")
    public Response<Map<String, Object>> interpret(@RequestBody InterpretRequest request,
                                                    @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
                                                    @RequestHeader(value = "X-Tenant-Id", required = false) String headerTenantId) {
        try {
            String userId = firstNonBlank(request.getUserId(), headerUserId);
            String tenantId = firstNonBlank(request.getTenantId(), headerTenantId);
            return success(service.interpret(request.getMessage(), request.getSessionId(), userId,
                    tenantId, request.getAiAgentId(), request.getMaxStep()));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        } catch (Exception e) {
            log.error("Interpret background task failed", e);
            return failure(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @GetMapping
    public Response<List<Map<String, Object>>> list(@RequestParam String userId,
                                                    @RequestParam(required = false) String sessionId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        try {
            return success(service.list(userId, sessionId, limit));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @PostMapping("/{taskId}/activate")
    public Response<Map<String, Object>> activate(@PathVariable String taskId,
                                                  @RequestBody UserRequest request) {
        try {
            return success(service.activate(taskId, request.getUserId()));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @PostMapping("/{taskId}/{action:pause|resume|cancel}")
    public Response<Map<String, Object>> changeStatus(@PathVariable String taskId,
                                                      @PathVariable String action,
                                                      @RequestBody UserRequest request) {
        try {
            return success(service.changeStatus(taskId, request.getUserId(), action));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @PostMapping("/{taskId}/run-now")
    public Response<Map<String, Object>> runNow(@PathVariable String taskId,
                                                @RequestBody UserRequest request) {
        try {
            return success(scheduler.runNow(taskId, request.getUserId()));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @PostMapping("/{taskId}/edit")
    public Response<Map<String, Object>> edit(@PathVariable String taskId,
                                              @RequestBody EditRequest request) {
        try {
            return success(service.edit(taskId, request.getUserId(), request.getName(), request.getTrigger(),
                    request.getActionPrompt(), request.getActionAgentId(), request.getMaxStep(), request.getRunOnce()));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    @GetMapping("/{taskId}/history")
    public Response<List<Map<String, Object>>> history(@PathVariable String taskId,
                                                       @RequestParam String userId,
                                                       @RequestParam(defaultValue = "30") int limit) {
        try {
            return success(service.history(taskId, userId, limit));
        } catch (IllegalArgumentException e) {
            return failure(ResponseCode.ILLEGAL_PARAMETER, e.getMessage());
        }
    }

    private static <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info("success")
                .data(data)
                .build();
    }

    private static <T> Response<T> failure(ResponseCode code, String message) {
        return Response.<T>builder()
                .code(code.getCode())
                .info(message == null ? code.getInfo() : message)
                .build();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        return second == null || second.isBlank() ? null : second.trim();
    }

    @Data
    public static class InterpretRequest {
        private String message;
        private String sessionId;
        private String userId;
        private String tenantId;
        private String aiAgentId;
        private Integer maxStep;
    }

    @Data
    public static class UserRequest {
        private String userId;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class EditRequest extends UserRequest {
        private String name;
        private Map<String, Object> trigger;
        private String actionPrompt;
        private String actionAgentId;
        private Integer maxStep;
        private Boolean runOnce;
    }
}
