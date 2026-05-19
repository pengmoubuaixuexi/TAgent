package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 上下文前缀生成器（P1.4 7.3 Contextual Retrieval）。
 * <p>
 * 为分片后的每个 chunk 生成 1-2 句上下文描述（"这个片段来自文档 X，讨论 Y"），
 * 拼到 chunk 正文前存入向量库，显著提升检索召回率（Anthropic 论文 +49%）。
 * <p>
 * 实现可用 LLM（Haiku 等小模型）或规则模板；null 返回表示跳过该 chunk（无副作用）。
 */
public interface IContextualPrefixGenerator {

    /**
     * 为一批 chunk 生成上下文前缀。
     * @param documentTitle 文档标题/文件名
     * @param chunks        切片后的 chunk 列表
     * @return 与 chunks 一一对应的前缀列表（同 size）；单个元素可为 null 表示跳过
     */
    List<String> generate(String documentTitle, List<Document> chunks);
}
