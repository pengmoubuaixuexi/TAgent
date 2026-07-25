package cn.bugstack.ai.trigger.background;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platform command router. It deliberately sits outside UnifiedAgentRouter:
 * this router only recognizes and structures background-task commands.
 */
@Slf4j
@Service
public class BackgroundTaskCommandRouter {

    private static final Pattern WINDOWS_PATH =
            Pattern.compile("(?i)([a-z]:\\\\[^\\r\\n，。；;]+)");
    private static final Pattern RELATIVE_FILE =
            Pattern.compile("(?i)([\\w.\\-\\u4e00-\\u9fa5]+\\.(?:md|txt|json|yaml|yml|xml|csv|log|java|js|ts|py))");
    private static final Pattern QUIET_DURATION =
            Pattern.compile("(\\d+)\\s*(秒|秒钟|s|sec|分钟|分|m|min)", Pattern.CASE_INSENSITIVE);

    private static final String PROMPT = """
            You are BackgroundTaskCommandRouter, a platform command parser. You do not answer the
            user's request and you do not choose Fixed/Auto/Flow. Decide whether the message is a
            command to create or manage a persistent background task.

            Supported operations: CREATE, LIST, PAUSE, RESUME, CANCEL.
            Supported task types:
            - FILE_CHANGE_STABLE: watch one local file and trigger after its content changed and then
              remained unchanged for quiet_seconds.
            - SCHEDULE_ONCE: trigger once at trigger_at, an ISO-8601 local datetime.
            - CRON: trigger repeatedly using a six-field Spring cron expression and zone_id.
            The only action is RUN_AGENT_PROMPT. action_prompt is the complete instruction that the
            Agent should execute after the trigger; it must retain the user's real intent and relevant
            paths, not merely say "notify me".

            Output exactly one JSON object matching this application schema. Do not use markdown:
            {
              "matched": true,
              "confidence": 0.0,
              "operation": "CREATE|LIST|PAUSE|RESUME|CANCEL",
              "needs_clarification": false,
              "clarifying_questions": [],
              "task_reference": null,
              "task_draft": {
                "task_type": "FILE_CHANGE_STABLE|SCHEDULE_ONCE|CRON",
                "name": "short human readable name",
                "trigger": {
                  "path": "required only for FILE_CHANGE_STABLE",
                  "quiet_seconds": 120,
                  "trigger_at": "required only for SCHEDULE_ONCE",
                  "cron_expression": "required only for CRON",
                  "zone_id": "Asia/Shanghai"
                },
                "action_prompt": "complete Agent instruction after trigger",
                "action_agent_id": null,
                "max_step": 5,
                "run_once": true
              }
            }
            For LIST/PAUSE/RESUME/CANCEL, task_draft must be null. PAUSE/RESUME/CANCEL must set
            task_reference to the task name or id from the user. If this is an ordinary immediate
            question or Agent action, return {"matched":false,"confidence":1.0,"operation":null,
            "needs_clarification":false,"clarifying_questions":[],"task_reference":null,"task_draft":null}.
            Never invent a file path, time, interval, cron, or action. Mark needs_clarification=true
            when a required value is absent.

            Current local time: %s Asia/Shanghai
            Current session id: %s
            User message:
            %s
            """;

    @Resource
    private ApplicationContext applicationContext;

    @Value("${agent.background-task.router-client-id:${agent.intent-router.client-id:router-small}}")
    private String routerClientId;

    public boolean mightBeBackgroundTask(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        boolean triggerWord = lower.contains("监视") || lower.contains("监听") || lower.contains("监控")
                || lower.contains("定时") || lower.contains("提醒") || lower.contains("每天")
                || lower.contains("每周") || lower.contains("每隔") || lower.contains("cron")
                || lower.contains("background task") || lower.contains("后台任务")
                || lower.contains("对象监视");
        boolean management = (lower.contains("暂停") || lower.contains("恢复") || lower.contains("取消")
                || lower.contains("列出") || lower.contains("查看"))
                && (lower.contains("任务") || lower.contains("监视器") || lower.contains("提醒"));
        return triggerWord || management;
    }

    public BackgroundTaskCommand route(String message, String sessionId) {
        if (!mightBeBackgroundTask(message)) {
            return BackgroundTaskCommand.builder().matched(false).confidence(1.0).build();
        }
        String raw = null;
        try {
            ChatClient client = applicationContext.getBean(
                    AiAgentEnumVO.AI_CLIENT.getBeanName(routerClientId), ChatClient.class);
            String prompt = PROMPT.formatted(
                    java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
                    sessionId == null ? "" : sessionId,
                    message);
            raw = client.prompt().user(prompt).call().content();
            BackgroundTaskCommand parsed = parse(raw);
            if (parsed != null) return parsed;
        } catch (Exception e) {
            log.warn("[BackgroundTaskRouter] LLM route failed clientId={}: {}", routerClientId, e.getMessage());
        }

        BackgroundTaskCommand fallback = fallbackExplicitFileMonitor(message);
        if (fallback != null) {
            fallback.setRawResponse(raw);
            return fallback;
        }
        return BackgroundTaskCommand.builder()
                .matched(true)
                .confidence(0.0)
                .needsClarification(true)
                .clarifyingQuestions(List.of("我识别到你想创建或管理后台任务，但没有解析出完整参数，请补充触发条件和触发后要做的事。"))
                .rawResponse(raw)
                .error("background_task_command_parse_failed")
                .build();
    }

    public BackgroundTaskCommand parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JSONObject json = JSON.parseObject(stripJsonFence(raw));
            BackgroundTaskCommand command = BackgroundTaskCommand.builder()
                    .matched(Boolean.TRUE.equals(json.getBoolean("matched")))
                    .confidence(json.getDoubleValue("confidence"))
                    .operation(json.getString("operation"))
                    .needsClarification(Boolean.TRUE.equals(json.getBoolean("needs_clarification")))
                    .clarifyingQuestions(stringList(json.getJSONArray("clarifying_questions")))
                    .taskReference(json.getString("task_reference"))
                    .rawResponse(raw)
                    .build();
            JSONObject draftJson = json.getJSONObject("task_draft");
            if (draftJson != null) {
                JSONObject triggerJson = draftJson.getJSONObject("trigger");
                Map<String, Object> trigger = new LinkedHashMap<>();
                if (triggerJson != null) trigger.putAll(triggerJson);
                command.setTaskDraft(BackgroundTaskCommand.TaskDraft.builder()
                        .taskType(draftJson.getString("task_type"))
                        .name(draftJson.getString("name"))
                        .trigger(trigger)
                        .actionPrompt(draftJson.getString("action_prompt"))
                        .actionAgentId(draftJson.getString("action_agent_id"))
                        .maxStep(draftJson.getInteger("max_step"))
                        .runOnce(draftJson.getBoolean("run_once"))
                        .build());
            }
            return command;
        } catch (Exception e) {
            log.warn("[BackgroundTaskRouter] invalid JSON: {}", e.getMessage());
            return null;
        }
    }

    private BackgroundTaskCommand fallbackExplicitFileMonitor(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (!(lower.contains("监视") || lower.contains("监听") || lower.contains("对象监视"))) return null;
        Matcher pathMatcher = WINDOWS_PATH.matcher(message);
        String path = pathMatcher.find() ? pathMatcher.group(1).trim() : null;
        if (path == null) {
            Matcher relative = RELATIVE_FILE.matcher(message);
            if (relative.find()) path = relative.group(1);
        }
        Matcher durationMatcher = QUIET_DURATION.matcher(message);
        Integer quietSeconds = null;
        if (durationMatcher.find()) {
            int value = Integer.parseInt(durationMatcher.group(1));
            String unit = durationMatcher.group(2).toLowerCase(Locale.ROOT);
            quietSeconds = (unit.startsWith("分") || unit.equals("m") || unit.equals("min")) ? value * 60 : value;
        }
        List<String> questions = new ArrayList<>();
        if (path == null) questions.add("要监视哪个本地文件？");
        if (quietSeconds == null) questions.add("文件变化后需要保持多久不再变化才触发？");
        Map<String, Object> trigger = new LinkedHashMap<>();
        if (path != null) trigger.put("path", path);
        if (quietSeconds != null) trigger.put("quiet_seconds", quietSeconds);
        String name = path == null ? "文件变化监视" : "监视 " + path;
        String action = "后台文件监视任务已触发。请读取并检查文件 " + (path == null ? "" : path)
                + " 的最新内容，结合原始要求继续处理。原始要求：" + message;
        return BackgroundTaskCommand.builder()
                .matched(true)
                .confidence(0.72)
                .operation("CREATE")
                .needsClarification(!questions.isEmpty())
                .clarifyingQuestions(questions)
                .taskDraft(BackgroundTaskCommand.TaskDraft.builder()
                        .taskType("FILE_CHANGE_STABLE")
                        .name(name)
                        .trigger(trigger)
                        .actionPrompt(action)
                        .maxStep(5)
                        .runOnce(true)
                        .build())
                .build();
    }

    private static List<String> stringList(JSONArray array) {
        if (array == null) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object value : array) {
            if (value != null && !String.valueOf(value).isBlank()) result.add(String.valueOf(value));
        }
        return result;
    }

    private static String stripJsonFence(String raw) {
        String value = raw.trim();
        int thinkEnd = value.lastIndexOf("</think>");
        if (thinkEnd >= 0) value = value.substring(thinkEnd + 8).trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                value = value.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }
}
