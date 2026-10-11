package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.api.dto.AiClientApiRequestDTO;
import cn.bugstack.ai.infrastructure.dao.IAiClientApiDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientApi;
import cn.bugstack.ai.trigger.http.admin.AiClientApiAdminController;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ModelApiSecretRedactionTest {
    @Test public void administrativeReadNeverReturnsPlaintext(){
        var dao=mock(IAiClientApiDao.class);var row=new AiClientApi();row.setApiKey("synthetic-platform-secret");
        when(dao.queryByApiId("api")).thenReturn(row);
        var controller=new AiClientApiAdminController();ReflectionTestUtils.setField(controller,"aiClientApiDao",dao);
        assertEquals("••••••••",controller.queryAiClientApiByApiId("api").getData().getApiKey());
    }
    @Test public void maskedOrEmptyEditPreservesExistingCredential(){
        for(String supplied:new String[]{null,"","••••••••"}){
            var dao=mock(IAiClientApiDao.class);var row=new AiClientApi();row.setApiKey("synthetic-platform-secret");
            when(dao.queryByApiId("api")).thenReturn(row);
            var controller=new AiClientApiAdminController();ReflectionTestUtils.setField(controller,"aiClientApiDao",dao);
            var request=new AiClientApiRequestDTO();request.setApiId("api");request.setApiKey(supplied);
            controller.updateAiClientApiByApiId(request);
            var saved=ArgumentCaptor.forClass(AiClientApi.class);verify(dao).updateByApiId(saved.capture());
            assertEquals("synthetic-platform-secret",saved.getValue().getApiKey());
        }
    }
    @Test public void requestToStringExcludesCredentials(){
        var request=new AiClientApiRequestDTO();request.setApiKey("synthetic-platform-secret");
        assertFalse(request.toString().contains("synthetic-platform-secret"));
    }
}
