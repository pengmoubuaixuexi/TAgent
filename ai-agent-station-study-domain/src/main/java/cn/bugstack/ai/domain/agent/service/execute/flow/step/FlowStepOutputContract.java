package cn.bugstack.ai.domain.agent.service.execute.flow.step;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the canonical handoff contract for every Flow DAG step.
 * The next step consumes the "输出数据" field, so that field must contain the
 * planned deliverable itself instead of metadata that merely describes it.
 */
public final class FlowStepOutputContract {

    private static final Pattern EXPECTED_OUTPUT = Pattern.compile(
            "(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?预期输出(?:\\*+)?\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern SUCCESS_CRITERIA = Pattern.compile(
            "(?im)^\\s*(?:[-*+]\\s*)?(?:\\*+)?成功标准(?:\\*+)?\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern CODE_DELIVERABLE = Pattern.compile(
            "代码|源码|脚本|source\\s*code|script", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_DELIVERABLE = Pattern.compile(
            "文档|报告|方案|配置|提示词|prompt|markdown", Pattern.CASE_INSENSITIVE);

    private FlowStepOutputContract() {
    }

    public static String render(String stepContent) {
        String expected = extract(EXPECTED_OUTPUT, stepContent);
        String success = extract(SUCCESS_CRITERIA, stepContent);

        StringBuilder contract = new StringBuilder();
        contract.append("**【本步骤输出契约（强制）】**\n");
        if (expected != null) {
            contract.append("- 计划规定的预期输出：").append(expected).append("\n");
        } else {
            contract.append("- 以【步骤内容】中的目标能力和执行方法所要求的最终交付物为准。\n");
        }
        if (success != null) {
            contract.append("- 计划规定的成功标准：").append(success).append("\n");
        }
        contract.append("- 回复末尾的『输出数据』必须直接包含上述交付物本身；"
                + "执行报告、文件名、路径、数量、类型、图表清单和摘要元数据都不能替代交付物。\n");

        String classificationSource = (expected == null ? "" : expected) + "\n"
                + (success == null ? "" : success);
        if (CODE_DELIVERABLE.matcher(classificationSource).find()) {
            contract.append("- 本步骤是代码交付：『输出数据』必须包含从 import/声明到最后一行的完整可运行代码。"
                    + "即使本步骤已经调用工具把代码写入文件，也仍须传递完整代码，不能只传 file_created、路径、函数清单或图表配置。\n");
        } else if (DOCUMENT_DELIVERABLE.matcher(classificationSource).find()) {
            contract.append("- 本步骤是正文交付：『输出数据』必须包含完整正文，不能只传标题、目录、文件路径或内容摘要。\n");
        } else {
            contract.append("- 本步骤是数据/结果交付：『输出数据』必须保留后继步骤完成任务所需的全部字段和值，不能只给概况。\n");
        }
        return contract.toString();
    }

    static String expectedOutput(String stepContent) {
        return extract(EXPECTED_OUTPUT, stepContent);
    }

    private static String extract(Pattern pattern, String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) return null;
        String result = matcher.group(1).replaceAll("\\*+$", "").trim();
        return result.isBlank() ? null : result;
    }
}
