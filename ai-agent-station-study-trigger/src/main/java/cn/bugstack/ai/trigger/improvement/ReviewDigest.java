package cn.bugstack.ai.trigger.improvement;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The evidence a review sees: the run, the owner's feedback or evaluation result, the node
 * configuration the run used and a bounded line per trace node. Pure functions over loaded data.
 */
final class ReviewDigest {

    static final int CONFIG_CHARS = 40_000;
    static final int TRACE_CHARS = 30_000;
    static final int NODE_LINE_CHARS = 400;
    static final Set<String> EDITABLE_FIELDS = Set.of("systemPrompt", "outputRequirement", "taskPrompt");
    private static final Set<String> EVENTS = Set.of("agent_routed", "step_start", "step_end", "model_fallback", "run_budget_notice",
            "rag_evidence", "memory_evidence", "attachment_notice", "user_input_required", "user_input_result",
            "human_approval_required", "human_approval_result");
    /** Failures a change to the agent cannot fix. */
    private static final Pattern EXTERNAL = Pattern.compile(
            "(所有模型都不可用|模型不可用|暂不可用|余额|额度|quota|rate.?limit|429|5\\d\\d|timed? ?out|超时|Connection|connect|"
                    + "网络|MCP|连接失败|enqueue|unreachable|Service Unavailable|Bad Gateway)", Pattern.CASE_INSENSITIVE);

    private ReviewDigest() { }

    /** Feedback (run review) or the evaluation result (failed evaluation case) the owner wants explained. */
    record Complaint(String rating, String comment, String correctedAnswer, String evalStatus, String evalError,
                     Double evalScore, String evalWarnings) { }

    /** Node texts the review saw in full; only these may be changed automatically. */
    record Config(String text, Set<String> completeNodes, Map<String, JsonNode> nodes) { }

    static Config config(JsonNode agentConfig, Integer version) {
        StringBuilder out = new StringBuilder();
        Set<String> complete = new HashSet<>();
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        if (agentConfig == null || !agentConfig.path("nodes").isArray()) {
            return new Config("（读取不到这次运行使用的节点配置）", complete, nodes);
        }
        out.append("执行模式：").append(agentConfig.path("strategy").asText("?"))
                .append("；运行使用的版本：").append(version == null ? "未知" : "v" + version).append('\n');
        for (JsonNode node : agentConfig.path("nodes")) {
            String id = node.path("nodeId").asText();
            nodes.put(id, node);
            StringBuilder block = new StringBuilder();
            block.append("\n### 节点 ").append(id).append("（角色 ").append(node.path("role").asText())
                    .append("，名称 ").append(node.path("name").asText()).append("）\n");
            for (String field : List.of("systemPrompt", "outputRequirement", "taskPrompt")) {
                String text = node.path(field).asText("");
                if (text.isBlank() && !"systemPrompt".equals(field)) continue;
                block.append(field).append("：\n").append(text).append('\n');
            }
            block.append("工具：绑定 MCP 连接 ").append(node.path("mcpBindings").size()).append(" 个；公共工具池")
                    .append(node.path("usePublicTools").asBoolean(false) ? "开" : "关").append("；运行 Python 代码")
                    .append(node.path("codeExecution").asBoolean(true) ? "开" : "关");
            List<String> advisors = new ArrayList<>();
            for (JsonNode advisor : node.path("advisors")) advisors.add(advisor.path("type").asText());
            block.append("；增强：").append(advisors.isEmpty() ? "无" : String.join("、", advisors)).append('\n');
            if (out.length() + block.length() <= CONFIG_CHARS) {
                out.append(block);
                complete.add(id);
            } else {
                out.append("\n### 节点 ").append(id).append("（内容过长未列出，不能自动修改）\n");
            }
        }
        return new Config(out.toString(), complete, nodes);
    }

    static String run(Map<String, Object> run, Complaint complaint) {
        StringBuilder out = new StringBuilder();
        out.append("- 用户问题：").append(clip(text(run.get("question")), 2000)).append('\n');
        out.append("- 运行状态：").append(text(run.get("status")));
        if (run.get("last_error") != null) out.append("（错误：").append(clip(text(run.get("last_error")), 1000)).append('）');
        out.append('\n');
        if (run.get("answer") != null) out.append("- 最终回答：\n").append(clip(text(run.get("answer")), 3000)).append('\n');
        if (complaint != null) {
            if (complaint.rating() != null) {
                out.append("- 用户评价：").append("HELPFUL".equals(complaint.rating()) ? "有帮助" : "需要改进");
                if (complaint.comment() != null && !complaint.comment().isBlank()) out.append("；说明：").append(clip(complaint.comment(), 1000));
                out.append('\n');
                if (complaint.correctedAnswer() != null && !complaint.correctedAnswer().isBlank())
                    out.append("- 用户认为正确的答案：\n").append(clip(complaint.correctedAnswer(), 2000)).append('\n');
            }
            if (complaint.evalStatus() != null) {
                out.append("- 评测结果：").append(complaint.evalStatus());
                if (complaint.evalScore() != null) out.append("，规则得分 ").append(Math.round(complaint.evalScore() * 100)).append("/100");
                if (complaint.evalError() != null) out.append("，错误：").append(clip(complaint.evalError(), 800));
                if (complaint.evalWarnings() != null && !complaint.evalWarnings().isBlank())
                    out.append("，提示：").append(clip(complaint.evalWarnings(), 800));
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Platform helper requests in a run's trace. A bare code such as CHAT_HELPER was read as part of
     * answering (a memory extraction replying NONE was cited as "no weather facts were added").
     */
    static String purpose(String code) {
        return switch (code) {
            case "CHAT_HELPER" -> "平台后台处理（长期记忆抽取、知识检索判断等），不参与生成回答";
            case "EMBEDDING" -> "向量检索，不参与生成回答";
            case "RERANK" -> "检索结果重排，不参与生成回答";
            case "EVAL_JUDGE" -> "评测打分，不参与生成回答";
            case "RUN_REVIEW" -> "运行复盘，不参与生成回答";
            default -> code;
        };
    }

    /** One line per model request, tool call and key event; failures are always kept. */
    static String trace(List<Map<String, Object>> nodes) {
        List<String> lines = new ArrayList<>();
        Map<String, String> stepNames = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            Map<?, ?> summary = node.get("summary") instanceof Map<?, ?> m ? m : Map.of();
            if ("EVENT".equals(node.get("kind")) && summary.get("stepId") != null && summary.get("displayName") != null)
                stepNames.put(text(summary.get("stepId")), text(summary.get("displayName")));
        }
        for (Map<String, Object> node : nodes) {
            String kind = text(node.get("kind")), name = text(node.get("name"));
            Map<?, ?> s = node.get("summary") instanceof Map<?, ?> m ? m : Map.of();
            if ("EVENT".equals(kind) && !EVENTS.contains(name)) continue;
            String step = node.get("step") == null ? "" : "[" + stepNames.getOrDefault(text(node.get("step")), text(node.get("step"))) + "] ";
            StringBuilder line = new StringBuilder("#").append(node.get("seq")).append(' ');
            switch (kind) {
                case "MODEL" -> {
                    line.append("模型请求 ").append(step).append(name).append(' ').append(text(node.get("status")))
                            .append(" 耗时").append(node.get("durationMs")).append("ms");
                    if (node.get("inputTokens") != null) line.append(" 输入").append(node.get("inputTokens")).append("/输出").append(node.get("outputTokens")).append("Token");
                    if (s.get("purpose") != null) line.append(" 用途：").append(purpose(text(s.get("purpose"))));
                    if (s.get("toolCalls") != null) line.append(" → 请求调用工具 ").append(s.get("toolCalls"));
                    else if (s.get("reply") != null) line.append(" → 回复：").append(s.get("reply"));
                    if (s.get("error") != null) line.append(" 错误：").append(s.get("error"));
                }
                case "TOOL" -> {
                    line.append("工具调用 ").append(step).append(name).append(' ').append(text(node.get("status")))
                            .append(" 耗时").append(node.get("durationMs")).append("ms 参数 ").append(s.get("arguments"))
                            .append(" 返回(").append(s.get("resultChars")).append("字)：").append(s.get("result"));
                    if (s.get("error") != null) line.append(" 错误：").append(s.get("error"));
                }
                default -> line.append("事件 ").append(name).append(' ').append(step)
                        .append(Objects.toString(s.get("notice"), Objects.toString(s.get("summary"), Objects.toString(s.get("data"), ""))));
            }
            lines.add(clip(line.toString(), NODE_LINE_CHARS));
        }
        StringBuilder out = new StringBuilder("共 ").append(nodes.size()).append(" 条记录\n");
        int budget = TRACE_CHARS;
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (budget - line.length() < 0 && !line.contains("ERROR") && !line.contains(" error")) continue;
            kept.add(line);
            budget -= line.length() + 1;
        }
        if (kept.size() < lines.size()) out.append("（记录较多，省略了 ").append(lines.size() - kept.size()).append(" 条成功的中间记录）\n");
        kept.forEach(line -> out.append(line).append('\n'));
        return out.toString();
    }

    /** A run that failed only because something outside the agent failed: a change to the agent would not help. */
    static String externalFailure(Map<String, Object> run, List<Map<String, Object>> nodes) {
        String status = text(run.get("status"));
        if (!Set.of("FAILED", "INTERRUPTED").contains(status)) return null;
        String error = text(run.get("last_error"));
        if (error.isBlank() || !EXTERNAL.matcher(error).find()) return null;
        boolean anySuccessfulAnswer = nodes.stream().anyMatch(n -> "MODEL".equals(n.get("kind")) && "OK".equals(n.get("status"))
                && n.get("summary") instanceof Map<?, ?> s && s.get("reply") != null);
        // A run that produced answers and then failed on a provider error is still reviewed: earlier steps may be at fault too.
        if (anySuccessfulAnswer && !error.contains("所有模型都不可用")) return null;
        return clip(error, 500);
    }

    static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…（共 " + text.length() + " 字）";
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
