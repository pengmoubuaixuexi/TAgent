package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import cn.bugstack.ai.domain.agent.service.rag.IParentEnrichmentGenerator.Enrichment;
import cn.bugstack.ai.domain.agent.service.rag.IParentEnrichmentGenerator;
import org.springframework.ai.retry.NonTransientAiException;
import cn.bugstack.ai.domain.agent.service.rag.ParentEnrichmentGenerator;
import org.junit.Before;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 不接 Spring / 不调真模型：验证合并调用的解析、修复重试、降级和规则模式。 */
public class ParentEnrichmentGeneratorTest {

    private ChatClient client;
    private ChatClient.CallResponseSpec response;
    private ParentEnrichmentGenerator generator;
    private final List<Document> children = List.of(new Document("c1", "该方法在三个数据集上提升 3.5%。", new java.util.HashMap<>()),
            new Document("c2", "上述实验使用 ResNet-50 作为骨干网络。", new java.util.HashMap<>()));

    @Before
    public void setup() {
        client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        response = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt(any(Prompt.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(eq("ai_client_router-small"), eq(ChatClient.class))).thenReturn(client);
        generator = new ParentEnrichmentGenerator(context);
        ReflectionTestUtils.setField(generator, "clientId", "router-small");
        ReflectionTestUtils.setField(generator, "titleEnabled", true);
        ReflectionTestUtils.setField(generator, "contextEnabled", true);
    }

    private void reply(String... contents) {
        var stub = when(response.content());
        if (contents.length == 1) stub.thenReturn(contents[0]);
        else stub.thenReturn(contents[0], Arrays.copyOfRange(contents, 1, contents.length));
    }

    @Test
    public void oneCallProducesTitleAndContextForEveryChild() {
        reply("```json\n{\"title\":\"《实验结果》\",\"contexts\":{\"1\":\"论文提出的方法的提升幅度\",\"2\":\"实验设置中的骨干网络\"}}\n```");
        Enrichment result = generator.enrich("paper.pdf", children);
        assertEquals("实验结果", result.title());
        assertEquals(List.of("论文提出的方法的提升幅度", "实验设置中的骨干网络"), result.contexts());
        verify(response, times(1)).content();
    }

    @Test
    public void invalidJsonIsRepairedOnceThenAccepted() {
        reply("这一段讲的是实验结果", "{\"title\":\"实验结果\",\"contexts\":[\"甲\",\"乙\"]}");
        Enrichment result = generator.enrich("paper.pdf", children);
        assertEquals("实验结果", result.title());
        assertEquals(List.of("甲", "乙"), result.contexts());
        verify(response, times(2)).content();
    }

    @Test
    public void twoFailuresDegradeWithoutBlockingIngestion() {
        reply("not json", "still not json");
        Enrichment result = generator.enrich("paper.pdf", children);
        assertNull(result.title());
        assertTrue(result.degraded());
        assertEquals(Arrays.asList(null, null), result.contexts());
        verify(response, times(2)).content();
    }

    @Test
    public void missingContextsStayNullAndOnlyEnabledPartsAreApplied() {
        reply("{\"title\":\"实验结果\",\"contexts\":{\"2\":\"乙\"}}");
        assertEquals(Arrays.asList(null, "乙"), generator.enrich("paper.pdf", children).contexts());
        ReflectionTestUtils.setField(generator, "titleEnabled", false);
        assertNull(generator.enrich("paper.pdf", children).title());
    }

    @Test(expected = RunBudgetExceededException.class)
    public void budgetExhaustionStopsInsteadOfDegrading() {
        when(response.content()).thenThrow(new RuntimeException(new RunBudgetExceededException("今日平台额度已用完")));
        generator.enrich("paper.pdf", children);
    }

    @Test
    public void clientErrorMeansModelUnavailableWithoutSecondAttempt() {
        when(response.content()).thenThrow(new NonTransientAiException("400 - {\"error\":{\"message\":\"Unsupported model mimo-v2.5\"}}"));
        IParentEnrichmentGenerator.UnavailableException error = assertThrows(IParentEnrichmentGenerator.UnavailableException.class,
                () -> generator.enrich("paper.pdf", children));
        assertTrue(error.getMessage().contains("HTTP 400"));
        verify(response, times(1)).content();
    }

    @Test
    public void rateLimitIsRetriedThenDegradedNotTreatedAsUnavailable() {
        when(response.content()).thenThrow(new NonTransientAiException("429 - Too Many Requests"));
        Enrichment result = generator.enrich("paper.pdf", children);
        assertTrue(result.degraded());
        assertNull(result.title());
        verify(response, times(2)).content();
    }

    @Test
    public void missingClientIsUnavailable() {
        ReflectionTestUtils.setField(generator, "clientId", "");
        assertThrows(IParentEnrichmentGenerator.UnavailableException.class, () -> generator.enrich("paper.pdf", children));
    }

    @Test
    public void ruleModeUsesHeadingWithoutModelCalls() {
        ReflectionTestUtils.setField(generator, "mode", "rule");
        List<Document> markdown = List.of(new Document("c1", "## 安装步骤\n先安装依赖。", new java.util.HashMap<>()),
                new Document("c2", "然后启动服务。", new java.util.HashMap<>()));
        Enrichment result = generator.enrich("guide.md", markdown);
        assertEquals("安装步骤", result.title());
        assertEquals(List.of("文档：guide.md；章节：安装步骤", "文档：guide.md；章节：安装步骤"), result.contexts());
        verifyNoInteractions(client);
        assertTrue(generator.strategy().startsWith("rule-v1"));
    }

    @Test
    public void disabledEnrichmentMakesNoCalls() {
        ReflectionTestUtils.setField(generator, "titleEnabled", false);
        ReflectionTestUtils.setField(generator, "contextEnabled", false);
        assertEquals(Arrays.asList(null, null), generator.enrich("paper.pdf", children).contexts());
        assertEquals("none", generator.strategy());
        verifyNoInteractions(client);
    }

    @Test
    public void titleCleaning() {
        ReflectionTestUtils.setField(generator, "maxTitleChars", 30);
        assertNull(generator.cleanTitle("   \n "));
        assertEquals("膝盖位置", generator.cleanTitle("小标题: 膝盖位置"));
        assertEquals("Knee Position", generator.cleanTitle("Title: Knee Position"));
        assertEquals("膝盖位置", generator.cleanTitle("《膝盖位置》"));
        assertEquals("膝盖位置", generator.cleanTitle("\"膝盖位置。\""));
        assertEquals(30, generator.cleanTitle("很".repeat(40)).length());
    }
}
