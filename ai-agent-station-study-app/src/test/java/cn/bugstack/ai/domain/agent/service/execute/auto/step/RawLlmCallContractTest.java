package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallGateway;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 raw helper 保留机器字段，同时仍经过 Gateway 与统一 Recorder。 */
public class RawLlmCallContractTest {

    private static final class Seam extends AbstractExecuteSupport {
        void dependencies(LlmCallGateway gateway, LlmObservationRecorder recorder) {
            this.llmCallGateway = gateway;
            this.llmObservationRecorder = recorder;
        }

        String raw(Supplier<ChatClient.CallResponseSpec> supplier, String step, String prompt) {
            return callChatClientRawWithLogging(supplier, step, prompt);
        }

        @Override
        protected String doApply(ExecuteCommandEntity requestParameter,
                                 DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            return null;
        }

        @Override
        public StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> get(
                ExecuteCommandEntity requestParameter,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            return defaultStrategyHandler;
        }
    }

    @Test
    public void rawHelperPreservesHtmlAndRecordsSuccessfulGatewayResponse() {
        LlmCallGateway gateway = mock(LlmCallGateway.class);
        LlmObservationRecorder recorder = mock(LlmObservationRecorder.class);
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("<!-- AUTO_QUALITY_VERDICT: PASS -->"))));
        when(gateway.call(any())).thenReturn(response);
        Supplier<ChatClient.CallResponseSpec> supplier = () -> mock(ChatClient.CallResponseSpec.class);

        Seam seam = new Seam();
        seam.dependencies(gateway, recorder);
        String raw = seam.raw(supplier, "step3_quality_verdict_repair", "repair prompt");

        assertEquals("<!-- AUTO_QUALITY_VERDICT: PASS -->", raw);
        verify(gateway).call(supplier);
        ArgumentCaptor<LlmCallContext> context = ArgumentCaptor.forClass(LlmCallContext.class);
        ArgumentCaptor<ChatResponse> recordedResponse = ArgumentCaptor.forClass(ChatResponse.class);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(recorder).record(context.capture(), recordedResponse.capture(), anyLong(), error.capture());
        assertEquals("step3_quality_verdict_repair", context.getValue().getStepName());
        assertEquals("repair prompt", context.getValue().getPrompt());
        assertEquals(raw, context.getValue().getResultText());
        assertSame(response, recordedResponse.getValue());
        assertNull(error.getValue());
    }

    @Test
    public void rawHelperKeepsFailureNullAndRecordsError() {
        LlmCallGateway gateway = mock(LlmCallGateway.class);
        LlmObservationRecorder recorder = mock(LlmObservationRecorder.class);
        when(gateway.call(any())).thenReturn(null);
        Supplier<ChatClient.CallResponseSpec> supplier = () -> mock(ChatClient.CallResponseSpec.class);

        Seam seam = new Seam();
        seam.dependencies(gateway, recorder);
        assertNull(seam.raw(supplier, "step3_quality_verdict_repair", "repair prompt"));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(recorder).record(any(LlmCallContext.class), org.mockito.ArgumentMatchers.isNull(), anyLong(), error.capture());
        assertTrue(error.getValue() instanceof IllegalStateException);
    }
}
