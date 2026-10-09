package cn.bugstack.ai.trigger.workspace;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authored templates, never copies of administrator prompts or secrets. */
public final class WorkspaceTemplates {
    private WorkspaceTemplates() {}
    public static final Set<String> ADVISORS = Set.of("ChatMemory", "LongTermMemory", "EpisodicMemory",
            "RagAnswer", "PiiMask", "PromptInjection");
    public static final String BUILDER_PROMPT = """
            你是 TAgent 编辑器内的搭建助手，帮助用户生成自己的节点配置骨架。
            页面入口是 /user-agents.html。Fixed 适合直接问答，Auto 适合分析、执行、质检、总结，Flow 适合规划并执行多步任务。
            创建流程：选择模式，在节点画布分别配置每个角色的模型和指令，选择本人 MCP 中的具体工具，按节点选择 Advisor，保存后开始对话。
            ChatMemory 是当前会话记忆；LongTermMemory 与 EpisodicMemory 是本人跨会话记忆；RagAnswer 只能选择本人的知识库；
            PiiMask 用于输入脱敏，PromptInjection 提供基础提示词注入防护。
            模型当前由平台提供；MCP 令牌仅写入服务端，不回显。个人 MCP 仅支持管理员允许的 HTTPS 服务，不能执行服务器命令。
            公共 Agent 的工具属于平台提供的能力，不能将平台令牌复制给用户。不要声称已经保存或发布任何配置。
            生成的骨架在同一编辑器预览并应用，之后逐节点微调。你不作为单独的对话 Agent 存在，不声称已保存或已运行。
            根据用户目标逐步给出具体建议，不虚构可用工具、模型、知识库或操作结果。
            """;
    public static List<Map<String, Object>> templates() {
        return List.of(template("fixed", "直接问答", "适合日常问答、翻译和单步任务"),
                template("auto", "分析与执行", "分析、执行、质检、总结，可逐步完成复杂问题"),
                template("flow", "规划工作流", "先规划步骤，再执行并整合结果"));
    }
    private static Map<String, Object> template(String strategy, String name, String description) {
        return Map.of("id", strategy, "name", name, "strategy", strategy, "description", description,
                "nodes", nodes(strategy),
                "systemPrompt", "你是一个可靠的助手。先理解用户目标，仅使用实际可用的工具；说明不确定之处，给出清晰、可验证的结果。");
    }
    public static boolean executable(String role) {
        return Set.of("DEFAULT", "PRECISION_EXECUTOR_CLIENT", "EXECUTOR_CLIENT").contains(role);
    }
    public static List<Map<String, Object>> nodes(String strategy) {
        return steps(strategy).stream().map(step -> Map.<String,Object>of(
                "nodeId", step.type().toLowerCase(java.util.Locale.ROOT), "role", step.type(), "name", step.name(),
                "systemPrompt", step.system(), "outputRequirement", "", "taskPrompt", "",
                "modelId", "", "mcpBindings", List.of(), "advisors", List.of(), "usePublicTools", executable(step.type()))).toList();
    }
    /** Read adapter only: legacy graphs keep running until their owner explicitly saves. */
    public static com.fasterxml.jackson.databind.node.ObjectNode upgrade(com.fasterxml.jackson.databind.JsonNode value,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) value.deepCopy();
        if (result.has("nodes")) return result;
        result.put("schemaVersion", 2);
        var nodes = result.putArray("nodes");
        for (var step : steps(result.path("strategy").asText())) {
            var node = nodes.addObject().put("nodeId", step.type().toLowerCase(java.util.Locale.ROOT))
                    .put("role", step.type()).put("name", step.name()).put("modelId", value.path("modelId").asText())
                    .put("systemPrompt", value.path("systemPrompt").asText() + "\n\n" + step.system())
                    .put("outputRequirement", "").put("taskPrompt", "").put("usePublicTools",false);
            node.set("advisors", value.has("advisors") ? value.get("advisors").deepCopy() : json.createArrayNode());
            var bindings = node.putArray("mcpBindings");
            if (executable(step.type())) for (var mcp : value.path("mcpIds"))
                bindings.addObject().put("mcpId", mcp.asText()).put("allTools", true).putArray("toolNames");
        }
        result.remove(java.util.List.of("modelId", "systemPrompt", "mcpIds", "advisors"));
        return result;
    }
    public static List<Map<String, String>> advisors() {
        return List.of(advisor("ChatMemory", "会话记忆", "读取当前会话的上下文"),
                advisor("LongTermMemory", "长期记忆", "检索本人的长期偏好和事实"),
                advisor("EpisodicMemory", "历史会话摘要", "读取本人的跨会话摘要"),
                advisor("RagAnswer", "知识库检索", "检索本人上传的指定知识库"),
                advisor("PiiMask", "输入脱敏", "对部分个人信息作基础脱敏"),
                advisor("PromptInjection", "提示词防护", "基础规则防护，不代表完全防御"));
    }
    private static Map<String, String> advisor(String type, String name, String description) {
        return Map.of("type", type, "name", name, "description", description);
    }
    public static String strategyBean(String strategy) {
        if (!Set.of("fixed", "auto", "flow").contains(strategy)) throw new IllegalArgumentException("请选择有效执行模式");
        return strategy + "AgentExecuteStrategy";
    }
    public record Step(String type, String name, String system, String prompt) {}
    public static List<Step> steps(String strategy) {
        return switch (strategy) {
            case "fixed" -> List.of(new Step("DEFAULT", "回答", "直接完成用户的请求。", "%s"));
            case "auto" -> List.of(
                    new Step("TASK_ANALYZER_CLIENT", "分析", "理解目标并制定下一步策略；本阶段不执行业务工具。",
                            "原始用户需求: %s\n当前执行步骤: 第 %d 步 (最大 %d 步)\n历史执行记录:\n%s\n当前任务: %s\n请分析并给出下一步策略。"),
                    new Step("PRECISION_EXECUTOR_CLIENT", "执行", "按照策略执行任务，仅报告真实执行结果。",
                            "用户需求: %s\n分析策略: %s\n请执行并产出结果。"),
                    new Step("QUALITY_SUPERVISOR_CLIENT", "质检", "核对结果是否满足用户需求，不编造工具执行结果。",
                            "用户需求: %s\n执行结果: %s\n请检查并给出质量评估、问题识别、改进建议、质量评分和是否通过。"),
                    new Step("RESPONSE_ASSISTANT", "总结", "整合已有证据，给出最终回答，不重复执行工具。",
                            "用户需求: %s\n执行记录: %s\n请整合并回答。"));
            case "flow" -> List.of(
                    new Step("TOOL_MCP_CLIENT", "工具分析", "分析实际工具能力。本阶段不执行业务工具，允许 request_tool 查询可用能力。", "%s"),
                    new Step("PLANNING_CLIENT", "步骤规划", "遵循运行时要求的 JSON 格式规划步骤，只使用真实工具。本阶段不执行业务工具。", "%s"),
                    new Step("EXECUTOR_CLIENT", "任务执行", "依据计划和工具结果执行任务，结果必须有证据。", "%s"));
            default -> throw new IllegalArgumentException("无效执行模式");
        };
    }
}
