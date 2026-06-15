package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 第 61 轮 Phase 2：parent title 生成器（A 方案 LLM chunk title）。
 * <p>
 * 跟 {@link IContextualPrefixGenerator} 类似，但产出"5-15 字精炼小标题"而不是"1-2 句上下文描述"。
 * 用途：写到 {@code ai_parent_document.title} 列，前端引用依据卡片显示这个标题，
 * 替代原始文件名（同一文件多 parent 时无法区分）或 parent_id UUID（完全不可读）。
 * <p>
 * 实现可用小模型 LLM 或规则模板；null 返回表示跳过该 parent（无 title，前端 fallback 到 source）。
 */
public interface IParentTitleGenerator {

    /**
     * 为一批 parent 块生成 5-15 字小标题。
     *
     * @param documentTitle 文档标题/文件名（给 LLM 上下文用）
     * @param parents       parent 切片后的 Document 列表
     * @return 与 parents 一一对应的标题列表（同 size）；单个元素可为 null 表示跳过
     */
    List<String> generate(String documentTitle, List<Document> parents);
}
