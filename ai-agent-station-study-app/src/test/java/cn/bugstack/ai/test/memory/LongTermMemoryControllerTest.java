package cn.bugstack.ai.test.memory;

import cn.bugstack.ai.api.dto.LongTermMemoryPageResponseDTO;
import cn.bugstack.ai.api.dto.LongTermMemoryResponseDTO;
import cn.bugstack.ai.api.dto.LongTermMemoryUpsertRequestDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryItem;
import cn.bugstack.ai.domain.agent.service.memory.longterm.LongTermMemoryPage;
import cn.bugstack.ai.trigger.http.LongTermMemoryController;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LongTermMemoryControllerTest {

    private LongTermMemoryController controller;
    private ILongTermMemoryService service;

    @Before
    public void setUp() {
        controller = new LongTermMemoryController();
        service = mock(ILongTermMemoryService.class);
        ReflectionTestUtils.setField(controller, "longTermMemoryService", service);
    }

    @Test
    public void missingIdentityIsRejectedBeforeServiceCall() {
        Response<LongTermMemoryPageResponseDTO> response = controller.list(null, 1, 20, null, null, null);

        assertEquals("0002", response.getCode());
        assertNull(response.getData());
        verify(service, never()).listForManagement(null, 1, 20, null, null, null);
    }

    @Test
    public void listUsesHeaderIdentityAndMapsPage() {
        LongTermMemoryPage page = LongTermMemoryPage.builder()
                .items(List.of(LongTermMemoryItem.builder()
                        .memoryId("m-1")
                        .topic("偏好:语言")
                        .content("偏好中文回答")
                        .source("auto")
                        .build()))
                .total(1)
                .page(1)
                .pageSize(20)
                .build();
        when(service.listForManagement("user-1", 1, 20, null, null, null)).thenReturn(page);

        Response<LongTermMemoryPageResponseDTO> response = controller.list(
                " user-1 ", 1, 20, null, null, null);

        assertEquals("0000", response.getCode());
        assertEquals(1L, response.getData().getTotal());
        assertEquals("m-1", response.getData().getItems().get(0).getMemoryId());
        verify(service).listForManagement("user-1", 1, 20, null, null, null);
    }

    @Test
    public void correctionUsesPathMemoryIdAndHeaderOwner() {
        LongTermMemoryUpsertRequestDTO request = LongTermMemoryUpsertRequestDTO.builder()
                .topic("技能:Java")
                .content("熟悉 Java 并发")
                .build();
        when(service.correctForManagement("user-1", "m-1", "熟悉 Java 并发", "技能:Java"))
                .thenReturn(LongTermMemoryItem.builder()
                        .memoryId("m-2")
                        .topic("技能:java")
                        .content("熟悉 Java 并发")
                        .source("manual")
                        .build());

        Response<LongTermMemoryResponseDTO> response = controller.correct("user-1", "m-1", request);

        assertEquals("0000", response.getCode());
        assertEquals("m-2", response.getData().getMemoryId());
        verify(service).correctForManagement("user-1", "m-1", "熟悉 Java 并发", "技能:Java");
    }
}
