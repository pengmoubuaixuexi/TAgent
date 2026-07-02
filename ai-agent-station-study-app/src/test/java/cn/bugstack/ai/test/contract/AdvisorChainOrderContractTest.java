package cn.bugstack.ai.test.contract;

import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.EpisodicMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.LongTermMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.element.ReadOnlyChatMemoryAdvisor;
import cn.bugstack.ai.domain.agent.service.memory.episodic.IEpisodicMemoryService;
import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Characterizes the real Spring AI advisor chain ordering without Spring, DB, or network. */
public class AdvisorChainOrderContractTest {

    @Test
    public void realAdvisorChainPreservesSystemDataHistoryAndUserOrder() {
        ILongTermMemoryService ltmService = mock(ILongTermMemoryService.class);
        when(ltmService.retrieveForInjection(any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of("[preference] 喜欢安静的酒店"));

        IEpisodicMemoryService episodicService = mock(IEpisodicMemoryService.class);
        when(episodicService.findBySessionIdForUser("user-1", "session-1"))
                .thenReturn("上轮已经确定预算");
        when(episodicService.getOtherSessions("user-1", "session-1", 5, 5))
                .thenReturn(List.of("曾讨论上海行程"));

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("知识库证据", new HashMap<>())));

        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("tenant-1:user-1:session-1")).thenReturn(List.of(
                new SystemMessage("【对话历史摘要】摘要内容"),
                new AssistantMessage("历史回答")));

        var ltm = new LongTermMemoryAdvisor(ltmService, 4, -100);
        var episodic = new EpisodicMemoryAdvisor(episodicService, 5, -80);
        var rag = new RagAnswerAdvisor(vectorStore, SearchRequest.builder().topK(2).build());
        var memory = new ReadOnlyChatMemoryAdvisor(chatMemory, 1);

        CapturingChatModel model = new CapturingChatModel().forStableId("RUNTIME-SYSTEM-ORDER");
        ChatClient client = ChatClient.builder(model)
                .defaultSystem("可信 L1 角色")
                .defaultAdvisors(ltm, episodic, rag, memory)
                .build();

        client.prompt().user("请规划周末行程")
                .advisors(a -> a
                        .param("chat_memory_conversation_id", "tenant-1:user-1:session-1")
                        .param("ltm_retrieval_query", "规划周末行程"))
                .call().content();

        WireSnapshot snapshot = model.lastSnapshot();
        assertEquals(List.of("SYSTEM", "SYSTEM", "SYSTEM", "ASSISTANT", "USER"), snapshot.roles());
        assertTrue(snapshot.messages().get(0).text().contains("可信 L1"));
        assertTrue(snapshot.messages().get(1).text().contains("知识库证据"));
        assertTrue(snapshot.messages().get(2).text().contains("对话历史摘要"));
        assertTrue(snapshot.messages().get(3).text().contains("历史回答"));
        String user = snapshot.messages().get(4).text();
        assertTrue(user.endsWith("请规划周末行程"));
    }

    @Test
    public void readOnlyChatMemoryCanBeDisabledPerRequestWithZeroRetrieveSize() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("tenant-1:user-1:session-1")).thenReturn(List.of(
                new UserMessage("old question"),
                new AssistantMessage("old answer")));

        var memory = new ReadOnlyChatMemoryAdvisor(chatMemory, 1);
        CapturingChatModel model = new CapturingChatModel().forStableId("MEMORY-ZERO");
        ChatClient client = ChatClient.builder(model)
                .defaultSystem("trusted system")
                .defaultAdvisors(memory)
                .build();

        client.prompt().user("current question")
                .advisors(a -> a
                        .param("chat_memory_conversation_id", "tenant-1:user-1:session-1")
                        .param("chat_memory_response_size", 0))
                .call().content();

        WireSnapshot snapshot = model.lastSnapshot();
        assertEquals(List.of("SYSTEM", "USER"), snapshot.roles());
        assertTrue(snapshot.messages().stream().noneMatch(m -> m.text().contains("old question")));
        assertTrue(snapshot.messages().stream().noneMatch(m -> m.text().contains("old answer")));
        assertTrue(snapshot.messages().get(1).text().contains("current question"));
    }

    @Test
    public void readOnlyChatMemoryHonorsPositiveRetrieveSize() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("tenant-1:user-1:session-1")).thenReturn(List.of(
                new UserMessage("old question 1"),
                new AssistantMessage("old answer 1"),
                new UserMessage("old question 2"),
                new AssistantMessage("old answer 2")));

        var memory = new ReadOnlyChatMemoryAdvisor(chatMemory, 1);
        CapturingChatModel model = new CapturingChatModel().forStableId("MEMORY-LIMIT");
        ChatClient client = ChatClient.builder(model)
                .defaultSystem("trusted system")
                .defaultAdvisors(memory)
                .build();

        client.prompt().user("current question")
                .advisors(a -> a
                        .param("chat_memory_conversation_id", "tenant-1:user-1:session-1")
                        .param("chat_memory_response_size", 1))
                .call().content();

        WireSnapshot snapshot = model.lastSnapshot();
        assertEquals(List.of("SYSTEM", "USER", "ASSISTANT", "USER"), snapshot.roles());
        assertTrue(snapshot.messages().stream().noneMatch(m -> m.text().contains("old question 1")));
        assertTrue(snapshot.messages().stream().noneMatch(m -> m.text().contains("old answer 1")));
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.text().contains("old question 2")));
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.text().contains("old answer 2")));
        assertTrue(snapshot.messages().get(3).text().contains("current question"));
    }

    @Test
    public void readOnlyChatMemoryDropsDanglingTrailingUserBeforeCurrentUser() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("tenant-1:user-1:session-1")).thenReturn(List.of(
                new UserMessage("stable history question"),
                new AssistantMessage("stable history answer"),
                new UserMessage("dangling historical question")));

        var memory = new ReadOnlyChatMemoryAdvisor(chatMemory, 1);
        CapturingChatModel model = new CapturingChatModel().forStableId("MEMORY-DANGLING-USER");
        ChatClient client = ChatClient.builder(model)
                .defaultSystem("trusted system")
                .defaultAdvisors(memory)
                .build();

        client.prompt().user("current question")
                .advisors(a -> a
                        .param("chat_memory_conversation_id", "tenant-1:user-1:session-1")
                        .param("chat_memory_response_size", 1024))
                .call().content();

        WireSnapshot snapshot = model.lastSnapshot();
        assertEquals(List.of("SYSTEM", "USER", "ASSISTANT", "USER"), snapshot.roles());
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.text().contains("stable history question")));
        assertTrue(snapshot.messages().stream().anyMatch(m -> m.text().contains("stable history answer")));
        assertTrue(snapshot.messages().stream().noneMatch(m -> m.text().contains("dangling historical question")));
        assertTrue(snapshot.messages().get(3).text().contains("current question"));
    }
}
