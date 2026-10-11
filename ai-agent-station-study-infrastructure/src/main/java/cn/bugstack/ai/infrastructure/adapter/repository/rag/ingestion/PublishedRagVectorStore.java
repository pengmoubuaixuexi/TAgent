package cn.bugstack.ai.infrastructure.adapter.repository.rag.ingestion;

import cn.bugstack.ai.domain.agent.service.rag.RagDocumentVisibility;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import java.util.List;

/** The application's public RAG VectorStore. All advisors share the publication gate, including direct searches. */
public final class PublishedRagVectorStore implements VectorStore {
    private final VectorStore delegate;
    private final RagDocumentVisibility visibility;
    public PublishedRagVectorStore(VectorStore delegate, RagDocumentVisibility visibility) { this.delegate = delegate; this.visibility = visibility; }
    @Override public List<Document> similaritySearch(SearchRequest request) {
        try (var guard = new RagReadGuard()) {
            int pool = Math.max(request.getTopK(), Math.min(1000, request.getTopK() * 4));
            List<Document> candidates = delegate.similaritySearch(SearchRequest.from(request).topK(pool).build());
            guard.check();
            List<Document> published = visibility.published(candidates).stream().limit(request.getTopK()).toList();
            guard.check();
            return published;
        }
    }
    @Override public void add(List<Document> documents) { delegate.add(documents); }
    @Override public void delete(List<String> ids) { delegate.delete(ids); }
    @Override public void delete(Filter.Expression filter) { delegate.delete(filter); }
}
