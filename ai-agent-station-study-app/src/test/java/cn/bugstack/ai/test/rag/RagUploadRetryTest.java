package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiRagOrderVO;
import cn.bugstack.ai.domain.agent.service.rag.IParentDocumentService;
import cn.bugstack.ai.domain.agent.service.rag.RagFileScope;
import cn.bugstack.ai.domain.agent.service.rag.RagService;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.domain.agent.service.rag.hybrid.BM25SearchService;
import cn.bugstack.ai.infrastructure.adapter.repository.rag.ParentDocumentService;
import cn.bugstack.ai.infrastructure.dao.IAiParentDocumentDao;
import cn.bugstack.ai.infrastructure.dao.po.AiParentDocument;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RagUploadRetryTest {
    @Test public void failedMirrorIsNotPublishedAndRetryReusesOwnerScopedIdentifiers() {
        RagService service = new RagService();
        IAgentRepository orders = mock(IAgentRepository.class);
        TextSplitter splitter = mock(TextSplitter.class);
        IParentDocumentService parents = mock(IParentDocumentService.class);
        BM25SearchService keywords = mock(BM25SearchService.class);
        ReflectionTestUtils.setField(service, "repository", orders);
        ReflectionTestUtils.setField(service, "textSplitter", splitter);
        ReflectionTestUtils.setField(service, "parentDocumentService", parents);
        ReflectionTestUtils.setField(service, "bm25SearchService", keywords);
        when(splitter.apply(anyList())).thenAnswer(invocation ->
                new ArrayList<>(List.of(new Document("A small offline fixture.", new HashMap<>()))));
        List<List<Document>> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            attempts.add(List.copyOf(invocation.getArgument(0)));
            if (attempts.size() == 1) throw new RagStorageException("fixture partial failure");
            return null;
        }).when(keywords).index(anyList());
        MockMultipartFile file = new MockMultipartFile("file", "fixture.txt", "text/plain",
                "A small offline fixture.".getBytes(StandardCharsets.UTF_8));

        assertThrows(RagStorageException.class, () -> service.storeRagFile("fixture", "kb", List.of(file), "alice"));
        verify(orders, never()).createTagOrder(any());
        service.storeRagFile("fixture", "kb", List.of(file), "alice");
        assertEquals(attempts.get(0).get(0).getId(), attempts.get(1).get(0).getId());
        UUID.fromString(attempts.get(1).get(0).getId()); // Valid PgVector UUID, not the parent label.
        String aliceFile = String.valueOf(attempts.get(1).get(0).getMetadata().get(RagFileScope.FILE_ID_KEY));
        assertTrue(aliceFile.startsWith("rf_"));
        service.storeRagFile("fixture", "kb", List.of(file), "bob");
        assertNotEquals(aliceFile, attempts.get(2).get(0).getMetadata().get(RagFileScope.FILE_ID_KEY));
        assertNotEquals(attempts.get(1).get(0).getId(), attempts.get(2).get(0).getId());
        ArgumentCaptor<AiRagOrderVO> published = ArgumentCaptor.forClass(AiRagOrderVO.class);
        verify(orders, times(2)).createTagOrder(published.capture());
        assertEquals(aliceFile, published.getAllValues().get(0).getRagId());
        assertEquals("alice", published.getAllValues().get(0).getUserId());
    }

    @Test public void parentRetryCannotOverwriteAnotherOwnersDocument() {
        ParentDocumentService service = new ParentDocumentService();
        IAiParentDocumentDao parents = mock(IAiParentDocumentDao.class);
        PgVectorStore vectors = mock(PgVectorStore.class);
        ReflectionTestUtils.setField(service, "parentDocumentDao", parents);
        ReflectionTestUtils.setField(service, "vectorStore", vectors);
        when(parents.findByParentId("same-id")).thenReturn(AiParentDocument.builder()
                .parentId("same-id").userId("bob").knowledgeTag("kb").build());
        assertThrows(RagStorageException.class, () -> service.store("same-id", "content", List.of(), "kb", "file.txt", "alice", null));
        verify(parents, never()).insert(any());
        verifyNoInteractions(vectors);
    }

    @Test public void missingOwnerIsRejectedBeforeReadingOrWritingFiles() {
        RagService service = new RagService();
        assertThrows(RagStorageException.class, () -> service.storeRagFile("fixture", "kb", List.of(), null));
    }
}
