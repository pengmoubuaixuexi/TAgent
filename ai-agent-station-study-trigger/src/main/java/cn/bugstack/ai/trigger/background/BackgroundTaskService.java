package cn.bugstack.ai.trigger.background;

import cn.bugstack.ai.infrastructure.dao.IAiBackgroundTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTask;
import cn.bugstack.ai.infrastructure.dao.po.AiBackgroundTaskExecution;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BackgroundTaskService {

    private static final List<String> TYPES = List.of("FILE_CHANGE_STABLE", "SCHEDULE_ONCE", "CRON");
    private static final List<String> TERMINAL = List.of("COMPLETED", "CANCELLED");

    private final IAiBackgroundTaskDao dao;
    private final BackgroundTaskCommandRouter router;

    @Transactional
    public Map<String, Object> interpret(String message,
                                         String sessionId,
                                         String userId,
                                         String tenantId,
                                         String selectedAgentId,
                                         Integer maxStep) {
        requireIdentity(sessionId, userId);
        BackgroundTaskCommand command = router.route(message, sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", command.isMatched());
        result.put("confidence", command.getConfidence());
        result.put("operation", command.getOperation());
        result.put("needsClarification", command.isNeedsClarification());
        result.put("clarifyingQuestions", command.getClarifyingQuestions());
        if (!command.isMatched()) return result;
        if (command.isNeedsClarification()) return result;

        String operation = upper(command.getOperation());
        if ("CREATE".equals(operation)) {
            BackgroundTaskCommand.TaskDraft draft = validateAndNormalize(command.getTaskDraft());
            LocalDateTime now = LocalDateTime.now();
            AiBackgroundTask task = AiBackgroundTask.builder()
                    .taskId(UUID.randomUUID().toString())
                    .userId(userId.trim())
                    .tenantId(blankToDefault(tenantId, "default"))
                    .sessionId(sessionId.trim())
                    .name(draft.getName().trim())
                    .taskType(draft.getTaskType())
                    .status("DRAFT")
                    .triggerConfigJson(JSON.toJSONString(draft.getTrigger()))
                    .actionPrompt(draft.getActionPrompt().trim())
                    .actionAgentId(blankToNull(draft.getActionAgentId()) != null
                            ? draft.getActionAgentId().trim() : blankToNull(selectedAgentId))
                    .maxStep(draft.getMaxStep() == null ? normalizeMaxStep(maxStep) : normalizeMaxStep(draft.getMaxStep()))
                    .runOnce(draft.getRunOnce() == null || draft.getRunOnce())
                    .nextTriggerAt(initialTriggerAt(draft))
                    .draftExpiresAt(now.plusMinutes(30))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            dao.insertTask(task);
            result.put("task", toView(task));
            result.put("message", "后台任务草稿已生成，请确认后启用。");
            return result;
        }

        if ("LIST".equals(operation)) {
            result.put("tasks", list(userId, null, 100));
            return result;
        }
        if (List.of("PAUSE", "RESUME", "CANCEL").contains(operation)) {
            String reference = requireText(command.getTaskReference(), "缺少要操作的任务名称或 taskId");
            AiBackgroundTask task = dao.findOwnedByReference(userId, reference.trim());
            if (task == null) throw new IllegalArgumentException("没有找到任务：" + reference);
            String target = switch (operation) {
                case "PAUSE" -> "PAUSED";
                case "RESUME" -> "ACTIVE";
                default -> "CANCELLED";
            };
            validateTransition(task, target);
            dao.updateStatusOwned(task.getTaskId(), userId, target, null);
            result.put("task", toView(dao.findOwned(task.getTaskId(), userId)));
            result.put("message", switch (operation) {
                case "PAUSE" -> "任务已暂停。";
                case "RESUME" -> "任务已恢复。";
                default -> "任务已取消。";
            });
            return result;
        }
        throw new IllegalArgumentException("不支持的后台任务操作：" + operation);
    }

    @Transactional
    public Map<String, Object> activate(String taskId, String userId) {
        AiBackgroundTask task = requireOwned(taskId, userId);
        if (!"DRAFT".equals(task.getStatus())) {
            throw new IllegalArgumentException("只有待确认草稿可以启用");
        }
        Map<String, Object> trigger = trigger(task);
        String baseline = null;
        LocalDateTime next = task.getNextTriggerAt();
        if ("FILE_CHANGE_STABLE".equals(task.getTaskType())) {
            Path path = Path.of(String.valueOf(trigger.get("path")));
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("监视文件不存在：" + path);
            baseline = sha256(path);
        } else if ("SCHEDULE_ONCE".equals(task.getTaskType())) {
            next = parseLocalDateTime(String.valueOf(trigger.get("trigger_at")));
            if (!next.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("一次性任务的触发时间必须晚于当前时间");
        } else if ("CRON".equals(task.getTaskType())) {
            next = schedulerLocal(nextCron(trigger, ZonedDateTime.now(zone(trigger))));
        }
        if (dao.activateDraft(taskId, userId, baseline, next) != 1) {
            throw new IllegalArgumentException("草稿已过期或状态已经改变");
        }
        return toView(requireOwned(taskId, userId));
    }

    public List<Map<String, Object>> list(String userId, String sessionId, int limit) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("缺少 userId");
        return dao.listOwned(userId.trim(), blankToNull(sessionId), Math.max(1, Math.min(limit, 200)))
                .stream().map(this::toView).toList();
    }

    public List<Map<String, Object>> history(String taskId, String userId, int limit) {
        requireOwned(taskId, userId);
        return dao.listExecutions(taskId, Math.max(1, Math.min(limit, 100)))
                .stream().map(this::executionView).toList();
    }

    @Transactional
    public Map<String, Object> changeStatus(String taskId, String userId, String action) {
        AiBackgroundTask task = requireOwned(taskId, userId);
        String target = switch (upper(action)) {
            case "PAUSE" -> "PAUSED";
            case "RESUME" -> "ACTIVE";
            case "CANCEL" -> "CANCELLED";
            default -> throw new IllegalArgumentException("不支持的操作：" + action);
        };
        validateTransition(task, target);
        dao.updateStatusOwned(taskId, userId, target, null);
        return toView(requireOwned(taskId, userId));
    }

    @Transactional
    public Map<String, Object> edit(String taskId,
                                    String userId,
                                    String name,
                                    Map<String, Object> trigger,
                                    String actionPrompt,
                                    String actionAgentId,
                                    Integer maxStep,
                                    Boolean runOnce) {
        AiBackgroundTask current = requireOwned(taskId, userId);
        BackgroundTaskCommand.TaskDraft draft = validateAndNormalize(BackgroundTaskCommand.TaskDraft.builder()
                .taskType(current.getTaskType())
                .name(name == null ? current.getName() : name)
                .trigger(trigger == null ? trigger(current) : new LinkedHashMap<>(trigger))
                .actionPrompt(actionPrompt == null ? current.getActionPrompt() : actionPrompt)
                .actionAgentId(actionAgentId == null ? current.getActionAgentId() : actionAgentId)
                .maxStep(maxStep == null ? current.getMaxStep() : maxStep)
                .runOnce(runOnce == null ? current.getRunOnce() : runOnce)
                .build());
        int updated = dao.updateDraftOwned(taskId, userId, draft.getName(),
                JSON.toJSONString(draft.getTrigger()), draft.getActionPrompt(),
                blankToNull(draft.getActionAgentId()), draft.getMaxStep(), draft.getRunOnce(),
                initialTriggerAt(draft), LocalDateTime.now().plusMinutes(30));
        if (updated != 1) throw new IllegalArgumentException("当前任务状态不能编辑");
        return toView(requireOwned(taskId, userId));
    }

    AiBackgroundTask requireOwned(String taskId, String userId) {
        if (taskId == null || taskId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少 taskId 或 userId");
        }
        AiBackgroundTask task = dao.findOwned(taskId.trim(), userId.trim());
        if (task == null) throw new IllegalArgumentException("任务不存在或无权访问");
        return task;
    }

    Map<String, Object> trigger(AiBackgroundTask task) {
        JSONObject parsed = JSON.parseObject(task.getTriggerConfigJson());
        return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    }

    Map<String, Object> toView(AiBackgroundTask task) {
        if (task == null) return null;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", task.getTaskId());
        view.put("sessionId", task.getSessionId());
        view.put("name", task.getName());
        view.put("taskType", task.getTaskType());
        view.put("status", task.getStatus());
        view.put("trigger", trigger(task));
        view.put("actionPrompt", task.getActionPrompt());
        view.put("actionAgentId", task.getActionAgentId());
        view.put("maxStep", task.getMaxStep());
        view.put("runOnce", task.getRunOnce());
        view.put("nextTriggerAt", task.getNextTriggerAt());
        view.put("lastTriggeredAt", task.getLastTriggeredAt());
        view.put("lastRunId", task.getLastRunId());
        view.put("lastError", task.getLastError());
        view.put("draftExpiresAt", task.getDraftExpiresAt());
        view.put("createdAt", task.getCreatedAt());
        view.put("updatedAt", task.getUpdatedAt());
        return view;
    }

    private Map<String, Object> executionView(AiBackgroundTaskExecution execution) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("executionId", execution.getExecutionId());
        view.put("runId", execution.getRunId());
        view.put("triggerReason", execution.getTriggerReason());
        view.put("status", execution.getStatus());
        view.put("startedAt", execution.getStartedAt());
        view.put("finishedAt", execution.getFinishedAt());
        view.put("errorMessage", execution.getErrorMessage());
        return view;
    }

    private BackgroundTaskCommand.TaskDraft validateAndNormalize(BackgroundTaskCommand.TaskDraft draft) {
        if (draft == null) throw new IllegalArgumentException("后台任务草稿为空");
        String type = upper(draft.getTaskType());
        if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的任务类型：" + type);
        draft.setTaskType(type);
        draft.setName(requireText(draft.getName(), "任务名称不能为空"));
        draft.setActionPrompt(requireText(draft.getActionPrompt(), "触发后的 Agent 指令不能为空"));
        if (draft.getTrigger() == null) draft.setTrigger(new LinkedHashMap<>());
        if ("FILE_CHANGE_STABLE".equals(type)) {
            String rawPath = requireText(value(draft.getTrigger(), "path"), "文件监视任务缺少 path");
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path);
            path = path.normalize().toAbsolutePath();
            int quiet = intValue(draft.getTrigger().get("quiet_seconds"), 120);
            if (quiet < 5 || quiet > 86400) throw new IllegalArgumentException("quiet_seconds 必须在 5 到 86400 之间");
            draft.getTrigger().put("path", path.toString());
            draft.getTrigger().put("quiet_seconds", quiet);
            if (draft.getRunOnce() == null) draft.setRunOnce(true);
        } else if ("SCHEDULE_ONCE".equals(type)) {
            String triggerAt = requireText(value(draft.getTrigger(), "trigger_at"), "一次性任务缺少 trigger_at");
            parseLocalDateTime(triggerAt);
            draft.getTrigger().put("trigger_at", triggerAt);
            draft.setRunOnce(true);
        } else {
            String expression = requireText(value(draft.getTrigger(), "cron_expression"), "周期任务缺少 cron_expression");
            CronExpression.parse(expression);
            String zoneId = value(draft.getTrigger(), "zone_id");
            if (zoneId == null || zoneId.isBlank()) zoneId = "Asia/Shanghai";
            ZoneId.of(zoneId);
            draft.getTrigger().put("cron_expression", expression);
            draft.getTrigger().put("zone_id", zoneId);
            draft.setRunOnce(false);
        }
        draft.setMaxStep(normalizeMaxStep(draft.getMaxStep()));
        return draft;
    }

    private LocalDateTime initialTriggerAt(BackgroundTaskCommand.TaskDraft draft) {
        if ("SCHEDULE_ONCE".equals(draft.getTaskType())) {
            return parseLocalDateTime(value(draft.getTrigger(), "trigger_at"));
        }
        if ("CRON".equals(draft.getTaskType())) {
            return schedulerLocal(nextCron(draft.getTrigger(), ZonedDateTime.now(zone(draft.getTrigger()))));
        }
        return null;
    }

    static ZonedDateTime nextCron(Map<String, Object> trigger, ZonedDateTime after) {
        CronExpression expression = CronExpression.parse(value(trigger, "cron_expression"));
        ZonedDateTime next = expression.next(after);
        if (next == null) throw new IllegalArgumentException("cron 表达式没有下一个触发时间");
        return next;
    }

    static ZoneId zone(Map<String, Object> trigger) {
        String zoneId = value(trigger, "zone_id");
        return ZoneId.of(zoneId == null || zoneId.isBlank() ? "Asia/Shanghai" : zoneId);
    }

    static LocalDateTime schedulerLocal(ZonedDateTime value) {
        return value.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    static LocalDateTime parseLocalDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(value).withZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException("时间必须是 ISO-8601 格式，例如 2026-07-25T10:00:00");
            }
        }
    }

    static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalArgumentException("读取监视文件失败：" + e.getMessage(), e);
        }
    }

    private static void validateTransition(AiBackgroundTask task, String target) {
        if (TERMINAL.contains(task.getStatus()) && !"CANCELLED".equals(target)) {
            throw new IllegalArgumentException("已结束的任务不能恢复或暂停");
        }
        if ("DRAFT".equals(task.getStatus()) && !"CANCELLED".equals(target)) {
            throw new IllegalArgumentException("草稿需要先确认启用");
        }
        if ("RUNNING".equals(task.getStatus()) && "PAUSED".equals(target)) {
            throw new IllegalArgumentException("当前触发的 run 正在执行，请先在会话中取消该 run");
        }
    }

    private static void requireIdentity(String sessionId, String userId) {
        requireText(sessionId, "缺少 sessionId");
        requireText(userId, "缺少 userId");
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int normalizeMaxStep(Integer maxStep) {
        return maxStep == null ? 5 : Math.max(1, Math.min(maxStep, 30));
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String value(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) return null;
        return String.valueOf(values.get(key));
    }
}
