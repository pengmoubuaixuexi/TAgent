package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.ai.test.contract.CapturingChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/** Test-only subclasses that execute the real doApply methods while replacing external boundaries. */
final class AutoNodeTestSeams {
    private AutoNodeTestSeams() {}

    static final class Step1 extends Step1AnalyzerNode {
        private String fixtureResult;
        private String capturedPrompt;
        private String thinkingContent;
        private Object mirroredValue;
        private final List<AutoAgentExecuteResultEntity> emitted = new ArrayList<>();
        private final ChatClient dummyClient = ChatClient.builder(new CapturingChatModel()).build();

        void result(String value) { fixtureResult = value; }
        String capturedPrompt() { return capturedPrompt; }
        String thinkingContent() { return thinkingContent; }
        Object mirroredValue() { return mirroredValue; }
        List<AutoAgentExecuteResultEntity> emitted() { return emitted; }
        String run(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) throws Exception {
            return doApply(req, ctx);
        }

        @Override protected String callStepWithSteer(
                Function<String, ChatClient.ChatClientRequestSpec> specBuilder,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                String stepId, String displayName,
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile,
                Supplier<String> basePromptSupplier, String sessionId) {
            // P0-B2a：捕获真实构造的 prompt（含 shadow 尾注），供测试断言
            this.capturedPrompt = basePromptSupplier.get();
            return fixtureResult;
        }
        @Override protected ChatClient getChatClientByClientId(String clientId) { return dummyClient; }
        @Override protected List<ToolCallback> resolveAgentDynamicToolCallbacks(ExecuteCommandEntity req, String clientId) { return List.of(); }
        @Override protected void checkCancelled(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {}
        @Override protected String checkFinalizeRoute(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) { return null; }
        @Override protected String metaToolPromptHint(String sessionId) { return ""; }
        @Override protected String appendCurrentTimeContext(String promptText) { return promptText; }
        @Override protected void sendThinkingEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx, String title, String content, String sessionId) { thinkingContent = content; }
        @Override protected void sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx, AutoAgentExecuteResultEntity result) { emitted.add(result); }
        @Override protected void mirrorToWorkingMemory(String sessionId, String key, Object value) { mirroredValue = value; }
        @Override protected void recordTransition(String fromStep, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {}
        @Override public String router(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) { return "ROUTED"; }
    }

    static final class Step3 extends Step3QualitySupervisorNode {
        private String fixtureResult;
        private String capturedPrompt;
        private Object mirroredValue;
        private final List<AutoAgentExecuteResultEntity> emitted = new ArrayList<>();
        private final CapturingChatModel capturingModel = new CapturingChatModel().forStableId("Auto-S3-REPAIR");
        private final ChatClient dummyClient = ChatClient.builder(capturingModel).build();

        void result(String value) { fixtureResult = value; }
        void repairResult(String value) { capturingModel.enqueue(value); }
        cn.bugstack.ai.test.contract.WireSnapshot repairSnapshot() { return capturingModel.lastSnapshot(); }
        int repairCalls() { return capturingModel.snapshots().size(); }
        String capturedPrompt() { return capturedPrompt; }
        Object mirroredValue() { return mirroredValue; }
        List<AutoAgentExecuteResultEntity> emitted() { return emitted; }
        String run(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) throws Exception {
            return doApply(req, ctx);
        }

        @Override protected String callStepWithStreaming(ChatClient.ChatClientRequestSpec spec,
                DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                String stepId, String displayName,
                cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile profile,
                String promptText, String sessionId) {
            // P0-B2a：捕获真实传入调用边界的 prompt（含 shadow 尾注），供测试断言
            this.capturedPrompt = promptText;
            return fixtureResult;
        }
        @Override protected String callChatClientRawWithLogging(
                Supplier<ChatClient.CallResponseSpec> specSupplier, String stepName, String promptText) {
            // 真实构造并穿过 ChatClient/Prompt/options，便于捕获 repair wire；仅替换 Gateway/Recorder 外界。
            return specSupplier.get().content();
        }
        @Override protected ChatClient getChatClientByClientId(String clientId) { return dummyClient; }
        @Override protected List<ToolCallback> resolveAgentDynamicToolCallbacks(ExecuteCommandEntity req, String clientId) { return List.of(); }
        @Override protected void checkCancelled(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {}
        @Override protected String checkFinalizeRoute(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) { return null; }
        @Override protected String metaToolPromptHint(String sessionId) { return ""; }
        @Override protected String appendCurrentTimeContext(String promptText) { return promptText; }
        @Override protected void sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx, AutoAgentExecuteResultEntity result) { emitted.add(result); }
        @Override protected void mirrorToWorkingMemory(String sessionId, String key, Object value) { mirroredValue = value; }
        @Override protected void recordTransition(String fromStep, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {}
        @Override public String router(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) { return "ROUTED"; }
    }

    static final class Step4 extends Step4LogExecutionSummaryNode {
        private int generateCalls;

        void metrics(cn.bugstack.ai.domain.agent.service.execute.common.AutoAgentMetrics value) {
            this.autoAgentMetrics = value;
        }
        int generateCalls() { return generateCalls; }
        String run(ExecuteCommandEntity req, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) throws Exception {
            return doApply(req, ctx);
        }

        @Override
        protected void generateFinalReport(ExecuteCommandEntity requestParameter,
                                           DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) {
            generateCalls++;
        }

        @Override protected void recordTransition(String fromStep, DefaultAutoAgentExecuteStrategyFactory.DynamicContext ctx) {}
    }
}
