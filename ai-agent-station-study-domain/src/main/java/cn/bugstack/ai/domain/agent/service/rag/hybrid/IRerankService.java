package cn.bugstack.ai.domain.agent.service.rag.hybrid;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Re-Rank 服务抽象（P1.4 7.2）。
 * <p>
 * 让 HybridRetriever 的下游可以从 LLM rerank（~3s）切换到 Cross-Encoder（~300ms），
 * 不改 RagAnswerAdvisor 一行代码。
 */
public interface IRerankService {

    /** 对候选池按 query 相关性重排，返回 topN */
    List<Document> rerank(String query, List<Document> candidates, int topN);
}
