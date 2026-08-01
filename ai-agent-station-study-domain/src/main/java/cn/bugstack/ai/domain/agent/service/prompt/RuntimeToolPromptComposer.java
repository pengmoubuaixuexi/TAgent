package cn.bugstack.ai.domain.agent.service.prompt;

import cn.bugstack.ai.domain.agent.service.execute.common.ExecutorToolCatalog;

/**
 * P2-B-3 RuntimeToolPromptComposer 渲染骨架（v3.md §68/§79.4）。
 *
 * <p>由 P2-A2 {@link ExecutorToolCatalog} 作<b>唯一 schema 来源</b>，生成 {@code <tool_runtime>}（工具清单 +
 * normalized schema，让模型不再猜参数）+ 元工具说明（收编 {@code metaToolPromptHint}）。
 *
 * <p><b>本骨架范围（CC）</b>：纯渲染函数。<b>待 Codex 接力</b>：①把 Flow Step1/Step2/Step4 现用
 * {@code AgentToolRegistry.getTools}（只有 name/description、无 schema）的工具注入点换成本 composer + 对应 V1/V2/V3 catalog；
 * ②把 {@code metaToolPromptHint} 的 profile/gate 判断接到 {@link #renderMetaToolHint}；
 * ③哪些场景从 warn 升 fail-closed（§79.4 留本波讨论）。
 */
public final class RuntimeToolPromptComposer {

    private RuntimeToolPromptComposer() {
    }

    /** 从 catalog 渲染 {@code <tool_runtime>}：每个工具 name + description + normalized schema。空 catalog 返回空串。 */
    public static String renderToolRuntime(ExecutorToolCatalog catalog) {
        if (catalog == null || catalog.toolNames().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<tool_runtime>\n");
        for (String name : catalog.toolNames()) {
            catalog.entry(name).ifPresent(e -> {
                sb.append("  <tool name=\"").append(escape(e.exactName())).append("\">\n");
                if (nonBlank(e.hintedDescription())) {
                    sb.append("    <description>").append(escape(e.hintedDescription())).append("</description>\n");
                }
                if (nonBlank(e.normalizedInputSchema())) {
                    sb.append("    <input_schema>").append(escape(e.normalizedInputSchema())).append("</input_schema>\n");
                }
                sb.append("  </tool>\n");
            });
        }
        sb.append("</tool_runtime>");
        return sb.toString();
    }

    /**
     * 元工具说明（收编 metaToolPromptHint 纯函数部分；profile/gate 判断由调用层算好布尔传入，
     * 保持与 RobustToolCallingManager 实际广播条件一致，文案与原 metaToolPromptHint 同口径）。
     */
    public static String renderMetaToolHint(boolean askUserAvailable, boolean requestToolAvailable) {
        if (!askUserAvailable && !requestToolAvailable) return "";
        StringBuilder sb = new StringBuilder("\n\n## 本阶段可用的元工具（例外）\n");
        sb.append("本阶段不执行用户的实际任务，但下列元工具属于例外、可按需调用（调用它们不算执行用户任务）：\n");
        if (askUserAvailable) {
            sb.append("- ask_user：关键信息缺失、或对用户意图有多种合理理解需用户拍板时，向用户提问澄清（一次把问题问全）；存在明确互斥答案时给出 2-4 个快捷选项并允许自由文本，无法可靠枚举时不必提供选项。\n");
        }
        if (requestToolAvailable) {
            sb.append("- request_tool：发现完成任务需要当前工具列表里没有的能力时，在 needs 里逐条用一句话描述所缺能力，预先装载真实工具供后续步骤使用。\n");
        }
        return sb.toString();
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
