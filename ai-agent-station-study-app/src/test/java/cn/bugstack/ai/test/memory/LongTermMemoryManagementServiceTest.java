package cn.bugstack.ai.test.memory;

import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryItem;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryPage;
import cn.bugstack.ai.infrastructure.adapter.repository.LongTermMemoryService;
import cn.bugstack.ai.infrastructure.dao.IAiLongTermMemoryDao;
import cn.bugstack.ai.infrastructure.dao.po.AiLongTermMemory;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LongTermMemoryManagementServiceTest {

    private LongTermMemoryService service;
    private IAiLongTermMemoryDao dao;
    private PgVectorStore vectorStore;

    @Before
    public void setUp() {
        service = new LongTermMemoryService();
        dao = mock(IAiLongTermMemoryDao.class);
        vectorStore = mock(PgVectorStore.class);
        ReflectionTestUtils.setField(service, "dao", dao);
        ReflectionTestUtils.setField(service, "ltmStore", vectorStore);
        ReflectionTestUtils.setField(service, "retrieveSimilarityThreshold", 0.0d);
        ReflectionTestUtils.setField(service, "embeddingQueryMaxChars", 6000);
    }

    @Test
    public void listManagementIsPureMetadataRead() {
        AiLongTermMemory row = memory("m-1", "user-1", "技能:java", "熟悉 Java");
        when(dao.findActivePage("user-1", null, null, null, 0, 20)).thenReturn(List.of(row));
        when(dao.countActive("user-1", null, null, null)).thenReturn(1L);

        LongTermMemoryPage page = service.listForManagement("user-1", 1, 20, null, null, null);

        assertEquals(1, page.getItems().size());
        assertEquals("m-1", page.getItems().get(0).getMemoryId());
        verify(dao, never()).touchAccess(any());
    }

    @Test
    public void semanticManagementSearchDoesNotTouchAccessStats() {
        Document document = Document.builder()
                .id("m-1")
                .text("熟悉 Java")
                .metadata(Map.of(
                        "type", "long_term_memory",
                        "user_id", "user-1",
                        "memory_id", "m-1",
                        "topic", "技能:java"))
                .score(0.92d)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(dao.findActiveMemoryIds("user-1")).thenReturn(List.of("m-1"));
        when(dao.findActiveOwned("user-1", "m-1"))
                .thenReturn(memory("m-1", "user-1", "技能:java", "熟悉 Java"));

        List<LongTermMemoryItem> result = service.searchForManagement("user-1", "编程语言", 10);

        assertEquals(1, result.size());
        assertEquals(0.92d, result.get(0).getSimilarity(), 0.0001d);
        verify(dao, never()).touchAccess(any());
    }

    @Test
    public void foreignMemoryCannotBeArchivedOrCorrected() {
        when(dao.findActiveOwned("user-1", "foreign-memory")).thenReturn(null);

        assertFalse(service.archiveForManagement("user-1", "foreign-memory"));
        try {
            service.correctForManagement("user-1", "foreign-memory", "新内容", "other");
            fail("foreign memory correction should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        verify(vectorStore, never()).delete(anyList());
    }

    @Test
    public void correctionWritesNewVectorAndArchivesOwnedOldVersion() {
        AiLongTermMemory old = memory("old-memory", "user-1", "技能:java", "会 Java");
        when(dao.findActiveOwned("user-1", "old-memory")).thenReturn(old);
        when(dao.findActiveOwned(eq("user-1"), argThat(id -> id != null && !"old-memory".equals(id))))
                .thenAnswer(invocation -> memory(invocation.getArgument(1), "user-1", "技能:java", "熟悉 Java 并发"));
        when(dao.archiveOwned("user-1", "old-memory")).thenReturn(1);

        LongTermMemoryItem corrected = service.correctForManagement(
                "user-1", "old-memory", "熟悉 Java 并发", "技能:Java");

        assertEquals("熟悉 Java 并发", corrected.getContent());
        assertEquals("技能:java", corrected.getTopic());
        verify(vectorStore).accept(anyList());
        verify(dao).insert(any(AiLongTermMemory.class));
        verify(dao).archiveOwned("user-1", "old-memory");
        verify(vectorStore).delete(List.of("old-memory"));
    }

    private static AiLongTermMemory memory(String memoryId, String userId, String topic, String content) {
        return AiLongTermMemory.builder()
                .id(1L)
                .memoryId(memoryId)
                .userId(userId)
                .tenantId("default")
                .topic(topic)
                .content(content)
                .source("manual")
                .accessCount(2)
                .lastAccessed(LocalDateTime.of(2026, 7, 18, 12, 0))
                .archived(0)
                .createdAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 7, 18, 12, 0))
                .build();
    }
}
