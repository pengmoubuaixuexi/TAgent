package cn.bugstack.ai.domain.agent.service.execute.common;

import com.alibaba.fastjson.JSON;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Transient, per-step wait feedback. Contains neither prompts nor tool arguments/results. */
public final class StreamingProgressEmitter {
    private StreamingProgressEmitter() {}

    public static void attach(StreamingActivityTracker.Activity activity, ResponseBodyEmitter emitter,
                              String stepId, String sessionId, String runId) {
        if (activity == null || emitter == null || stepId == null || stepId.isBlank()) return;
        activity.onProgress(progress -> {
            String message = switch (progress.phase()) {
                case "preparing_model" -> "正在准备模型请求";
                case "memory_retrieval" -> "正在检索记忆";
                case "rag_routing" -> "正在判断知识库检索策略";
                case "knowledge_retrieval" -> "正在检索知识库";
                case "knowledge_reranking" -> "正在筛选检索结果";
                case "awaiting_model" -> "等待模型响应";
                case "model_streaming" -> "模型正在生成";
                case "tool_running" -> "正在等待工具结果";
                case "awaiting_user" -> "等待你的补充或确认";
                case "artifact_generating" -> "正在生成产物内容";
                case "artifact_repairing" -> "产物参数有误，正在修复格式";
                case "artifact_saving" -> "正在保存产物";
                case "artifact_fallback" -> "产物未保存，正在保留回答正文";
                default -> null;
            };
            if (message == null) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stepId", stepId);
            if (sessionId != null && !sessionId.isBlank()) payload.put("sessionId", sessionId);
            if (runId != null && !runId.isBlank()) payload.put("runId", runId);
            payload.put("phase", progress.phase());
            payload.put("message", message);
            payload.put("waitSeconds", Math.max(0L, progress.waitSeconds()));
            payload.put("timestamp", System.currentTimeMillis());
            try {
                synchronized (emitter) {
                    emitter.send("event: step_progress\ndata: " + JSON.toJSONString(payload) + "\n\n");
                }
            } catch (Exception ignored) {
                // Browser disconnects must not change execution, tool safety or timeout accounting.
            }
        });
    }
}
