package cn.bugstack.ai.test.rag;

import cn.bugstack.ai.api.dto.AiClientRagOrderRequestDTO;
import cn.bugstack.ai.domain.agent.service.rag.IRagFileDeletionService;
import cn.bugstack.ai.domain.agent.service.rag.RagStorageException;
import cn.bugstack.ai.infrastructure.dao.IAiClientRagOrderDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientRagOrder;
import cn.bugstack.ai.trigger.http.admin.AiClientRagOrderAdminController;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RagDirectoryControllerTest {
    private AiClientRagOrderAdminController controller;
    private IAiClientRagOrderDao orders;

    @Before public void setup() {
        controller = new AiClientRagOrderAdminController(); orders = mock(IAiClientRagOrderDao.class);
        ReflectionTestUtils.setField(controller, "aiClientRagOrderDao", orders);
        when(orders.queryById(1L)).thenAnswer(ignored -> stored());
        when(orders.queryByRagId("rf_fixture")).thenAnswer(ignored -> stored());
        when(orders.updateById(any())).thenReturn(1);
    }

    @Test public void indexLocatorsCannotBeEditedIndependentlyOfStoredContent() {
        for (AiClientRagOrderRequestDTO request : new AiClientRagOrderRequestDTO[]{
                AiClientRagOrderRequestDTO.builder().id(1L).ragName("renamed.txt").build(),
                AiClientRagOrderRequestDTO.builder().id(1L).ragId("another").build(),
                AiClientRagOrderRequestDTO.builder().id(1L).knowledgeTag("another").build()}) {
            var response = controller.updateAiClientRagOrderById(request);
            assertFalse(response.getData()); assertTrue(response.getInfo().contains("不能仅修改目录"));
        }
        var response = controller.updateAiClientRagOrderByRagId(AiClientRagOrderRequestDTO.builder()
                .ragId("rf_fixture").knowledgeTag("another").build());
        assertFalse(response.getData());
        verify(orders, never()).updateById(any()); verify(orders, never()).updateByRagId(any());
    }

    @Test public void statusCanChangeWithoutClearingOwnershipOrStorageLocators() {
        var response = controller.updateAiClientRagOrderById(AiClientRagOrderRequestDTO.builder().id(1L).status(0).build());
        assertTrue(response.getData());
        verify(orders).updateById(argThat(row -> row.getStatus() == 0 && "alice".equals(row.getUserId())
                && "rf_fixture".equals(row.getRagId()) && "source.txt".equals(row.getRagName()) && "kb".equals(row.getKnowledgeTag())));
    }

    @Test public void deletionFailureIsVisibleWithoutLeakingBackendDetails() {
        IRagFileDeletionService deletion = mock(IRagFileDeletionService.class);
        ReflectionTestUtils.setField(controller, "ragFileDeletionService", deletion);
        when(deletion.deleteById(1L)).thenThrow(new RagStorageException("文件目录已保留，请重试。",
                new IllegalStateException("SYNTHETIC_SQL_PRIVATE")));
        var response = controller.deleteAiClientRagOrderById(1L);
        assertFalse(response.getData()); assertTrue(response.getInfo().contains("目录已保留"));
        assertFalse(response.getInfo().contains("SQL_PRIVATE"));
        doThrow(new IllegalStateException("SYNTHETIC_SQL_PRIVATE")).when(deletion).deleteById(1L);
        assertFalse(controller.deleteAiClientRagOrderById(1L).getInfo().contains("SQL_PRIVATE"));
    }

    private AiClientRagOrder stored() {
        return AiClientRagOrder.builder().id(1L).ragId("rf_fixture").ragName("source.txt")
                .knowledgeTag("kb").userId("alice").status(1).fileHash("fixture").build();
    }
}
