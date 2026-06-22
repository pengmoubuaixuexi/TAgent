package cn.bugstack.ai.test.prompt;

import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.EpisodicMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import cn.bugstack.ai.domain.agent.service.prompt.ContextEnvelopeComposer;
import cn.bugstack.ai.domain.agent.service.prompt.ContextEnvelopeRenderAdvisor;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-B-2：锁住 LTM/Episodic 采集与 ContextEnvelope 渲染的真实 advisor 组合边界。
 * <p>
 * 目标不是测 LLM/DB，而是防止 prompt 构造路径再次退化成「多个 advisor 分别改写 UserMessage」、
 * 或在改写时丢失 request options/toolContext。
 */
public class ContextEnvelopeAdvisorIntegrationTest {

    @After
    public void clearMdc() {
        MDC.clear();
    }

    @Test
    public void renderAdvisorWithoutEnvelopeContext_isNoopForOrdinaryUserMessage() {
        ChatClientRequest request = request("系统提示", "普通问题", options(), new LinkedHashMap<>());

        ChatClientRequest out = new ContextEnvelopeRenderAdvisor().before(request, null);

        assertSame("无 ctx.envelope.* 时必须 no-op，不能给普通请求强行套 <task>", request, out);
        assertEquals("普通问题", out.prompt().getUserMessage().getText());
        assertFalse(out.prompt().getUserMessage().getText().contains("<context_data"));
    }

    @Test
    public void renderAdvisorRendersSingleEnvelope_preservesSystemMessageAndOptions_thenClearsEnvelopeKeys() {
        OpenAiChatOptions options = options();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(ContextEnvelopeComposer.CTX_LTM, "[偏好:回答风格] 喜欢简洁");
        ctx.put(ContextEnvelopeComposer.CTX_EPISODIC, "刚聊过成都旅行");
        ctx.put(ContextEnvelopeComposer.CTX_OUTPUT_CONTRACT, "只输出 JSON");
        ctx.put("kept", "v");
        ChatClientRequest request = request("系统提示", "请规划 <成都> 行程", options, ctx);

        ChatClientRequest out = new ContextEnvelopeRenderAdvisor().before(request, null);

        assertSame(options, out.prompt().getOptions());
        assertEquals("系统提示", ((SystemMessage) out.prompt().getInstructions().get(0)).getText());
        String user = out.prompt().getUserMessage().getText();
        assertEquals(1, count(user, "<context_data"));
        assertTrue(user.contains("<long_term_memory>[偏好:回答风格] 喜欢简洁</long_term_memory>"));
        assertTrue(user.contains("<episodic_memory>刚聊过成都旅行</episodic_memory>"));
        assertTrue(user.contains("<task>请规划 &lt;成都&gt; 行程</task>"));
        assertTrue(user.contains("<output_contract>只输出 JSON</output_contract>"));
        assertEquals("v", out.context().get("kept"));
        assertFalse(out.context().containsKey(ContextEnvelopeComposer.CTX_LTM));
        assertFalse(out.context().containsKey(ContextEnvelopeComposer.CTX_EPISODIC));
        assertFalse(out.context().containsKey(ContextEnvelopeComposer.CTX_OUTPUT_CONTRACT));
    }

    @Test
    public void longTermMemoryBeforeWritesEnvelopeContext_keepsOriginalPromptAndOptions() {
        ILongTermMemoryService ltm = mock(ILongTermMemoryService.class);
        when(ltm.retrieveForInjection("u1", "原始问题", 30, 5))
                .thenReturn(List.of("[偏好:回答风格] 喜欢简洁", "[画像:职业] Java 后端工程师"));
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(ltm, 4);
        OpenAiChatOptions options = options();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(LongTermMemoryAdvisor.SESSION_CONTEXT_KEY, "tenant:u1:s1");
        ChatClientRequest request = request("系统提示", "原始问题", options, ctx);

        ChatClientRequest out = advisor.before(request, null);

        assertSame(request.prompt(), out.prompt());
        assertSame(options, out.prompt().getOptions());
        assertEquals("原始问题", out.prompt().getUserMessage().getText());
        assertTrue(String.valueOf(out.context().get(ContextEnvelopeComposer.CTX_LTM)).contains("[偏好:回答风格] 喜欢简洁"));
        assertFalse(out.prompt().getUserMessage().getText().contains("关于用户的已知信息"));
    }

    @Test
    public void episodicBeforeWritesEnvelopeContext_keepsOriginalPromptAndOptions() {
        IEpisodicMemoryService episodic = mock(IEpisodicMemoryService.class);
        when(episodic.findBySessionIdForUser("u1", "s1")).thenReturn("本会话在讨论旅行预算");
        when(episodic.getOtherSessions("u1", "s1", 5, 5)).thenReturn(List.of("上次聊过成都美食"));
        EpisodicMemoryAdvisor advisor = new EpisodicMemoryAdvisor(episodic, 5);
        OpenAiChatOptions options = options();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(EpisodicMemoryAdvisor.SESSION_CONTEXT_KEY, "tenant:u1:s1");
        ChatClientRequest request = request("系统提示", "继续规划", options, ctx);

        ChatClientRequest out = advisor.before(request, null);

        assertSame(request.prompt(), out.prompt());
        assertSame(options, out.prompt().getOptions());
        assertEquals("继续规划", out.prompt().getUserMessage().getText());
        String episodicSection = String.valueOf(out.context().get(ContextEnvelopeComposer.CTX_EPISODIC));
        assertTrue(episodicSection.contains("本会话在讨论旅行预算"));
        assertTrue(episodicSection.contains("上次聊过成都美食"));
        assertFalse(out.prompt().getUserMessage().getText().contains("当前会话已聊到"));
    }

    @Test
    public void ltmThenEpisodicThenRender_producesOneCanonicalEnvelope() {
        ILongTermMemoryService ltm = mock(ILongTermMemoryService.class);
        when(ltm.retrieveForInjection("u1", "帮我继续规划", 30, 5))
                .thenReturn(List.of("[偏好:回答风格] 喜欢简洁"));
        IEpisodicMemoryService episodic = mock(IEpisodicMemoryService.class);
        when(episodic.findBySessionIdForUser("u1", "s1")).thenReturn("当前会话在做旅行计划");
        when(episodic.getOtherSessions("u1", "s1", 5, 5)).thenReturn(List.of("历史会话提到预算 3000"));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put(LongTermMemoryAdvisor.SESSION_CONTEXT_KEY, "tenant:u1:s1");
        ChatClientRequest request = request("系统提示", "帮我继续规划", options(), ctx);

        ChatClientRequest afterLtm = new LongTermMemoryAdvisor(ltm, 4).before(request, null);
        ChatClientRequest afterEpisodic = new EpisodicMemoryAdvisor(episodic, 5).before(afterLtm, null);
        ChatClientRequest out = new ContextEnvelopeRenderAdvisor().before(afterEpisodic, null);

        String user = out.prompt().getUserMessage().getText();
        assertEquals(1, count(user, "<context_data"));
        assertTrue(user.contains("<long_term_memory>[偏好:回答风格] 喜欢简洁</long_term_memory>"));
        assertTrue(user.contains("<episodic_memory>"));
        assertTrue(user.contains("当前会话在做旅行计划"));
        assertTrue(user.contains("历史会话提到预算 3000"));
        assertTrue(user.contains("<task>帮我继续规划</task>"));
        assertFalse(out.context().containsKey(ContextEnvelopeComposer.CTX_LTM));
        assertFalse(out.context().containsKey(ContextEnvelopeComposer.CTX_EPISODIC));
    }

    @Test
    public void advisorOrder_placesRenderAfterMemoryBeforeRagAndChatMemory() {
        assertTrue(new LongTermMemoryAdvisor(mock(ILongTermMemoryService.class), 4).getOrder()
                < new EpisodicMemoryAdvisor(mock(IEpisodicMemoryService.class), 5).getOrder());
        assertTrue(new EpisodicMemoryAdvisor(mock(IEpisodicMemoryService.class), 5).getOrder()
                < new ContextEnvelopeRenderAdvisor().getOrder());
        assertTrue(new ContextEnvelopeRenderAdvisor().getOrder() < 0);
    }

    private static OpenAiChatOptions options() {
        return OpenAiChatOptions.builder()
                .maxTokens(321)
                .toolContext(Map.of("agent.run_id", "run-1"))
                .build();
    }

    private static ChatClientRequest request(String system, String user, OpenAiChatOptions options, Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(Prompt.builder()
                        .messages(new SystemMessage(system), new UserMessage(user))
                        .chatOptions(options)
                        .build())
                .context(context)
                .build();
    }

    private static int count(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
