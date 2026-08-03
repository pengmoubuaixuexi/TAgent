package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RobustToolCallingManager;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilities;
import cn.bugstack.ai.domain.agent.service.execute.common.ToolCapabilityProfile;
import cn.bugstack.ai.domain.agent.service.security.UserInputGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** ask_user 结构化快捷选项协议的兼容性测试。 */
public class AskUserStructuredOptionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class EmptyDelegate implements ToolCallingManager {
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            return ToolExecutionResult.builder().conversationHistory(prompt.getInstructions()).build();
        }
    }

    @Test
    public void toolDefinitionAdvertisesStructuredOptionsAndLegacyStringQuestions() throws Exception {
        RobustToolCallingManager manager = new RobustToolCallingManager(new EmptyDelegate());
        UserInputGate gate = org.mockito.Mockito.mock(UserInputGate.class);
        org.mockito.Mockito.when(gate.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(gate.remainingFor("session-1")).thenReturn(1);
        manager.setUserInputGate(gate);

        OpenAiChatOptions options = OpenAiChatOptions.builder().toolContext(Map.of(
                "sessionId", "session-1",
                ToolCapabilities.TOOL_CONTEXT_KEY, ToolCapabilityProfile.INTERACTIVE_META.name())).build();
        MDC.put("sessionId", "session-1");
        ToolDefinition definition;
        try {
            definition = manager.resolveToolDefinitions(options).stream()
                    .filter(item -> RobustToolCallingManager.ASK_USER_TOOL_NAME.equals(item.name()))
                    .findFirst().orElseThrow();
        } finally {
            MDC.remove("sessionId");
        }

        assertTrue(definition.description().contains("2-4 个具体 options"));
        assertTrue(definition.description().contains("allowFreeText=true"));
        assertTrue(definition.description().contains("禁止先将整个数组序列化成字符串"));
        JsonNode schema = MAPPER.readTree(definition.inputSchema());
        JsonNode alternatives = schema.path("properties").path("questions").path("items").path("oneOf");
        assertEquals(2, alternatives.size());
        assertEquals("string", alternatives.get(0).path("type").asText());
        JsonNode structured = alternatives.get(1);
        assertEquals("object", structured.path("type").asText());
        assertEquals(2, structured.path("properties").path("options").path("minItems").asInt());
        assertEquals(4, structured.path("properties").path("options").path("maxItems").asInt());
        assertTrue(structured.path("properties").path("allowFreeText").path("default").asBoolean());
        assertEquals("boolean", structured.path("properties").path("multiple").path("type").asText());
        assertFalse(structured.path("properties").path("multiple").path("default").asBoolean());
        assertTrue(definition.description().contains("multiple=true"));
    }

    @Test
    public void payloadKeepsLegacyQuestionsAsStringsAndAddsStructuredDetails() throws Exception {
        UserInputGate gate = new UserInputGate();
        ReflectionTestUtils.setField(gate, "timeoutSeconds", 60);
        String args = """
                {
                  "context":"缺少发布目标",
                  "questions":[
                    "是否需要保留草稿？",
                    {
                      "question":"请选择目标平台",
                      "options":[
                        {"label":"GitHub","description":"创建 GitHub 发布内容"},
                        {"label":"GitLab","value":"发布到 GitLab"}
                      ],
                      "allowFreeText":true,
                      "multiple":true
                    },
                    {
                      "question":"请选择可见范围",
                      "options":["公开","私有"],
                      "allowFreeText":false
                    },
                    {
                      "question":"您对哪些类型的景点更感兴趣？（可多选）",
                      "options":["历史文化","美食体验"]
                    }
                  ]
                }
                """;

        String payload = ReflectionTestUtils.invokeMethod(gate, "buildPayload", "input-1", args, "Step2");
        assertNotNull(payload);
        JsonNode root = MAPPER.readTree(payload);
        assertEquals("缺少发布目标", root.path("context").asText());
        assertEquals(List.of("是否需要保留草稿？", "请选择目标平台", "请选择可见范围", "您对哪些类型的景点更感兴趣？（可多选）"),
                MAPPER.convertValue(root.path("questions"), List.class));
        assertEquals("Step2", root.path("step").asText());

        JsonNode details = root.path("questionDetails");
        assertEquals(3, details.size());
        assertTrue(details.get(0).path("allowFreeText").asBoolean());
        assertTrue(details.get(0).path("multiple").asBoolean());
        assertEquals("GitHub", details.get(0).path("options").get(0).path("value").asText());
        assertEquals("创建 GitHub 发布内容",
                details.get(0).path("options").get(0).path("description").asText());
        assertEquals("发布到 GitLab", details.get(0).path("options").get(1).path("value").asText());
        assertFalse(details.get(1).path("allowFreeText").asBoolean());
        assertFalse(details.get(1).path("multiple").asBoolean());
        assertEquals("公开", details.get(1).path("options").get(0).path("value").asText());
        assertTrue("旧事件只在题目中写可多选时也应被识别",
                details.get(2).path("multiple").asBoolean());
    }

    @Test
    public void legacyPayloadShapeRemainsUnchanged() throws Exception {
        UserInputGate gate = new UserInputGate();
        ReflectionTestUtils.setField(gate, "timeoutSeconds", 60);
        String payload = ReflectionTestUtils.invokeMethod(gate, "buildPayload", "input-2",
                "{\"questions\":[\"问题一\",\"问题二\"]}", null);

        JsonNode root = MAPPER.readTree(payload);
        assertEquals(List.of("问题一", "问题二"), MAPPER.convertValue(root.path("questions"), List.class));
        assertFalse(root.has("questionDetails"));
        assertEquals(String.class,
                UserInputGate.class.getMethod("resolveUserInput", String.class, String.class)
                        .getParameterTypes()[1]);
    }

    @Test
    public void jsonEncodedQuestionsArrayMustBeUnwrappedInsteadOfProducingBlankModal() throws Exception {
        UserInputGate gate = new UserInputGate();
        ReflectionTestUtils.setField(gate, "timeoutSeconds", 60);
        String encodedQuestions = """
                [
                  {
                    "question":"你计划什么时候出发？比如\"下周五\"或\"8月中旬\"",
                    "options":[
                      {"label":"下周末","value":"8月8日"},
                      {"label":"还没确定","value":"待定"}
                    ]
                  },
                  {
                    "question":"你更想去哪类景点？可以多选",
                    "options":[
                      {"label":"历史文化","value":"历史文化"},
                      {"label":"美食体验","value":"美食体验"}
                    ],
                    "multiple":true
                  }
                ]
                """;
        String args = MAPPER.writeValueAsString(Map.of("questions", encodedQuestions));

        String payload = ReflectionTestUtils.invokeMethod(gate, "buildPayload", "input-encoded", args, "Step3");
        JsonNode root = MAPPER.readTree(payload);
        assertEquals(2, root.path("questions").size());
        assertEquals(2, root.path("questionDetails").size());
        assertTrue(root.path("questions").get(0).asText().contains("下周五"));
        assertEquals("下周末", root.path("questionDetails").get(0)
                .path("options").get(0).path("label").asText());
        assertTrue(root.path("questionDetails").get(1).path("multiple").asBoolean());

        RobustToolCallingManager manager = new RobustToolCallingManager(new EmptyDelegate());
        String preview = ReflectionTestUtils.invokeMethod(manager, "askUserPreview", args);
        assertTrue(preview.contains("1. 你计划什么时候出发"));
        assertTrue(preview.contains("[下周末 / 还没确定]"));
        assertTrue(preview.contains("2. 你更想去哪类景点"));
        assertFalse("卡片不应再显示原始 JSON", preview.startsWith("{\"questions\""));
    }

    @Test
    public void textualQuestionMustNeverBecomeAnEmptyInteraction() throws Exception {
        UserInputGate gate = new UserInputGate();
        ReflectionTestUtils.setField(gate, "timeoutSeconds", 60);
        String payload = ReflectionTestUtils.invokeMethod(gate, "buildPayload", "input-text",
                "{\"questions\":\"请告诉我出发日期\"}", null);

        JsonNode root = MAPPER.readTree(payload);
        assertEquals(1, root.path("questions").size());
        assertEquals("请告诉我出发日期", root.path("questions").get(0).asText());
    }
}
