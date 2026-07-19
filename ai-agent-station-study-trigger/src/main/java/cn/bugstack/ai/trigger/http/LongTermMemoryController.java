package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.dto.LongTermMemoryPageResponseDTO;
import cn.bugstack.ai.api.dto.LongTermMemoryResponseDTO;
import cn.bugstack.ai.api.dto.LongTermMemoryUpsertRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryItem;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryPage;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 当前登录用户的长期记忆管理 API。
 * <p>
 * userId 只从系统现有身份头 X-User-Id 解析，不接受 query/body 中的 userId，避免前端误操作他人数据。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/memories")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class LongTermMemoryController {

    private static final String USER_HEADER = "X-User-Id";

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @GetMapping
    public Response<LongTermMemoryPageResponseDTO> list(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword) {
        try {
            String owner = requireUserId(userId);
            LongTermMemoryPage result = longTermMemoryService.listForManagement(
                    owner, page, pageSize, topic, source, keyword);
            return success(toPageResponse(result));
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("查询长期记忆失败", e);
            return failure("查询长期记忆失败");
        }
    }

    @GetMapping("/search")
    public Response<List<LongTermMemoryResponseDTO>> semanticSearch(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            String owner = requireUserId(userId);
            List<LongTermMemoryResponseDTO> data = longTermMemoryService
                    .searchForManagement(owner, query, limit)
                    .stream().map(LongTermMemoryController::toResponse).toList();
            return success(data);
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("语义搜索长期记忆失败", e);
            return failure("语义搜索失败，请稍后重试");
        }
    }

    @PostMapping
    public Response<LongTermMemoryResponseDTO> create(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @RequestBody(required = false) LongTermMemoryUpsertRequestDTO request) {
        try {
            String owner = requireUserId(userId);
            requireRequest(request);
            return success(toResponse(longTermMemoryService.createManual(
                    owner, request.getContent(), request.getTopic())));
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("手动新增长期记忆失败", e);
            return failure(readableMessage(e, "新增记忆失败，请稍后重试"));
        }
    }

    @PutMapping("/{memoryId}")
    public Response<LongTermMemoryResponseDTO> correct(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @PathVariable String memoryId,
            @RequestBody(required = false) LongTermMemoryUpsertRequestDTO request) {
        try {
            String owner = requireUserId(userId);
            requireRequest(request);
            return success(toResponse(longTermMemoryService.correctForManagement(
                    owner, memoryId, request.getContent(), request.getTopic())));
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("纠正长期记忆失败 memoryId={}", memoryId, e);
            return failure(readableMessage(e, "纠正记忆失败，请稍后重试"));
        }
    }

    @DeleteMapping("/{memoryId}")
    public Response<Boolean> archive(
            @RequestHeader(value = USER_HEADER, required = false) String userId,
            @PathVariable String memoryId) {
        try {
            String owner = requireUserId(userId);
            boolean archived = longTermMemoryService.archiveForManagement(owner, memoryId);
            if (!archived) return illegal("记忆不存在或不属于当前用户");
            return success(true);
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            log.error("归档长期记忆失败 memoryId={}", memoryId, e);
            return failure(readableMessage(e, "归档记忆失败，请稍后重试"));
        }
    }

    private static String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("请先登录后再管理长期记忆");
        }
        return userId.trim();
    }

    private static void requireRequest(LongTermMemoryUpsertRequestDTO request) {
        if (request == null) throw new IllegalArgumentException("请求内容不能为空");
    }

    private static LongTermMemoryPageResponseDTO toPageResponse(LongTermMemoryPage page) {
        List<LongTermMemoryResponseDTO> items = page.getItems() == null ? List.of()
                : page.getItems().stream().map(LongTermMemoryController::toResponse).toList();
        return LongTermMemoryPageResponseDTO.builder()
                .items(items)
                .total(page.getTotal())
                .page(page.getPage())
                .pageSize(page.getPageSize())
                .build();
    }

    private static LongTermMemoryResponseDTO toResponse(LongTermMemoryItem item) {
        return LongTermMemoryResponseDTO.builder()
                .memoryId(item.getMemoryId())
                .topic(item.getTopic())
                .content(item.getContent())
                .source(item.getSource())
                .sourceSession(item.getSourceSession())
                .accessCount(item.getAccessCount())
                .lastAccessed(item.getLastAccessed())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .similarity(item.getSimilarity())
                .build();
    }

    private static String readableMessage(Exception e, String fallback) {
        if (!(e instanceof IllegalStateException)) return fallback;
        return e.getMessage() == null || e.getMessage().isBlank() ? fallback : e.getMessage();
    }

    private static <T> Response<T> success(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private static <T> Response<T> illegal(String message) {
        return Response.<T>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .data(null)
                .build();
    }

    private static <T> Response<T> failure(String message) {
        return Response.<T>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(message)
                .data(null)
                .build();
    }
}
