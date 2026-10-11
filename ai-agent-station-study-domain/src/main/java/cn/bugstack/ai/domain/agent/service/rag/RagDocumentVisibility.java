package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.document.Document;
import java.util.List;

/** Authoritative publication check shared by every RAG retrieval backend. */
@FunctionalInterface
public interface RagDocumentVisibility {
    List<Document> published(List<Document> candidates);
}
