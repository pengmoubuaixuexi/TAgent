package cn.bugstack.ai.test.workspace;

import cn.bugstack.ai.domain.agent.service.security.WorkspaceMcpPolicy;
import cn.bugstack.ai.trigger.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkspaceKnowledgeTagTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test public void commonDisplayPunctuationRemainsOneLiteralKnowledgeValue() {
        for (String tag : List.of("演示·Redis缓存策略（功能重录）", "Redis (2026)", "缓存_策略-v1.2")) {
            JdbcTemplate db = mock(JdbcTemplate.class);
            when(db.queryForObject(anyString(), eq(Integer.class), eq("alice"), eq(tag))).thenReturn(3);
            WorkspaceService service = service(db);
            ObjectNode result = ReflectionTestUtils.invokeMethod(service, "validateNodeResources",
                    "alice", node(tag), List.of(Map.of("modelId", "model")));
            assertEquals(tag, result.path("advisors").get(0).path("knowledgeTag").asText());
            var parsed = new FilterExpressionTextParser().parse("knowledge == '" + tag + "'");
            assertEquals(new Filter.Expression(Filter.ExpressionType.EQ,
                    new Filter.Key("knowledge"), new Filter.Value(tag)), parsed);
            verify(db).queryForObject(anyString(), eq(Integer.class), eq("alice"), eq(tag));
        }
    }

    @Test public void quoteBackslashAndControlCharactersAreStillRejectedBeforeLookup() {
        for (String tag : List.of("x' || user_id == 'bob", "x\\tag", "x\ny", "x\ty", "x;tag")) {
            JdbcTemplate db = mock(JdbcTemplate.class);
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service(db),
                    "validateNodeResources", "alice", node(tag), List.of(Map.of("modelId", "model"))));
            verifyNoInteractions(db);
        }
    }

    @Test public void punctuationDoesNotAllowBindingAnotherUsersKnowledge() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForObject(anyString(), eq(Integer.class), eq("alice"), anyString())).thenReturn(0);
        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service(db),
                "validateNodeResources", "alice", node("演示·Redis（私人）"), List.of(Map.of("modelId", "model"))));
        verify(db).queryForObject(anyString(), eq(Integer.class), eq("alice"), eq("演示·Redis（私人）"));
    }

    private WorkspaceService service(JdbcTemplate db) {
        return new WorkspaceService(db, json, null, null, null, mock(WorkspaceMcpPolicy.class), null, null);
    }

    private ObjectNode node(String tag) {
        ObjectNode n = json.createObjectNode().put("modelId", "model").put("systemPrompt", "根据本人资料回答");
        n.putArray("mcpBindings");
        n.putArray("advisors").addObject().put("type", "RagAnswer").put("topK", 12).put("knowledgeTag", tag);
        return n;
    }
}
