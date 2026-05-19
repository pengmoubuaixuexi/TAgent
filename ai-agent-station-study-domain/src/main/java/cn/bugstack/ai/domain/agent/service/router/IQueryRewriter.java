package cn.bugstack.ai.domain.agent.service.router;

/**
 * Query 改写器（P1.4）。
 * <p>
 * 用户原话 → 检索友好的"陈述句"。例：
 * <ul>
 *   <li>"它怎么用？" → "Spring AI VectorStore 的使用方法"</li>
 *   <li>"上面那个" → 还原成完整名词的检索语句</li>
 *   <li>"我刚才说的 X 怎么解决" → "X 的解决方案"</li>
 * </ul>
 * <p>
 * 改写发生在 RagAnswerAdvisor.before() 阶段，<b>仅替换检索 query</b>，原始消息照常进 prompt。
 * 这样模型答题用的还是用户原话，但向量库 / BM25 拿到的是更聚焦的检索 query，召回率显著提升。
 */
public interface IQueryRewriter {

    /**
     * @return 改写后的 query；任何失败/未配置都返回原 query（永不抛异常）
     */
    String rewrite(String original);
}
