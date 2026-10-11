package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.*;
import cn.bugstack.ai.domain.agent.service.security.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MeteredToolDebugGuardTest {
    @After public void clear(){MDC.clear();}
    @Test public void onlyExplicitReadOnlyWithKnownRiskRegistryCanExecute() {
        AtomicInteger calls=new AtomicInteger();
        HumanApprovalGate gate=mock(HumanApprovalGate.class);HighRiskToolRegistry risk=mock(HighRiskToolRegistry.class);
        when(gate.getHighRiskToolRegistry()).thenReturn(risk);
        for(Boolean readOnly:new Boolean[]{null,false,true}) {
            ToolCallback raw=new ToolCallback() {
                public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("search").description("search").inputSchema("{}").build();}
                public ToolMetadata getToolMetadata(){return new McpToolCallMetadata("owned",readOnly,false,null,false);}
                public String call(String input){calls.incrementAndGet();return "result";}
            };
            MeteredToolCallback callback=new MeteredToolCallback(raw,new McpToolMetrics(new SimpleMeterRegistry()));callback.setHumanApprovalGate(gate);
            String result=callback.call("{}",new ToolContext(Map.of(MeteredToolCallback.DEBUG_READ_ONLY_KEY,true)));
            assertEquals(Boolean.TRUE.equals(readOnly),result.equals("result"));
            if(!Boolean.TRUE.equals(readOnly))assertTrue(result.contains("DEBUG_TOOL_BLOCKED"));
        }
        assertEquals(1,calls.get());
    }
    @Test public void highRiskIsBlockedEvenIfApprovalGloballyDisabledAndCallbackClaimsReadOnly() {
        ToolCallback raw=mock(ToolCallback.class);when(raw.getToolDefinition()).thenReturn(ToolDefinition.builder().name("danger").description("danger").inputSchema("{}").build());
        when(raw.getToolMetadata()).thenReturn(new McpToolCallMetadata("owned",true,false,null,false));
        HumanApprovalGate gate=mock(HumanApprovalGate.class);HighRiskToolRegistry risk=mock(HighRiskToolRegistry.class);
        when(gate.getHighRiskToolRegistry()).thenReturn(risk);when(risk.isHighRisk("danger")).thenReturn(true);when(gate.isEnabled()).thenReturn(false);
        MeteredToolCallback callback=new MeteredToolCallback(raw,new McpToolMetrics(new SimpleMeterRegistry()));callback.setHumanApprovalGate(gate);
        assertTrue(callback.call("{}",new ToolContext(Map.of(MeteredToolCallback.DEBUG_READ_ONLY_KEY,true))).contains("DEBUG_TOOL_BLOCKED"));
        verify(raw,never()).call(anyString());
    }
}
