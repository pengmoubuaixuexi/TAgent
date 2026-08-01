package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.notification.AgentNotification;
import cn.bugstack.ai.domain.agent.service.notification.NotificationService;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/agent/notifications")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class NotificationController {

    private static final String USER_HEADER = "X-User-Id";
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Response<List<AgentNotification>> list(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        try {
            return success(notificationService.list(userId, limit, includeArchived));
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("List notifications failed", e);
            return failure("Failed to list notifications");
        }
    }

    @PostMapping("/{notificationId}/ack")
    public Response<AgentNotification> acknowledge(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @PathVariable String notificationId) {
        try {
            return success(notificationService.acknowledge(userId, notificationId));
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("Acknowledge notification failed notificationId={}", notificationId, e);
            return failure("Failed to acknowledge notification");
        }
    }

    @PostMapping("/{notificationId}/archive")
    public Response<AgentNotification> archive(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @PathVariable String notificationId) {
        try {
            return success(notificationService.archive(userId, notificationId));
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("Archive notification failed notificationId={}", notificationId, e);
            return failure("Failed to archive notification");
        }
    }

    private static <T> Response<T> success(T data) {
        return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(data).build();
    }

    private static <T> Response<T> illegal(String message) {
        return Response.<T>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(message).build();
    }

    private static <T> Response<T> failure(String message) {
        return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode()).info(message).build();
    }
}
