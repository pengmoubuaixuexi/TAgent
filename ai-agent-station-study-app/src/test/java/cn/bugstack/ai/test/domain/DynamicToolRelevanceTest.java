package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiMcpToolCatalogVO;
import cn.bugstack.ai.domain.agent.service.execute.common.McpClientRegistry;
import cn.bugstack.ai.domain.agent.service.router.IToolVectorStore;
import cn.bugstack.ai.domain.agent.service.router.McpToolCatalogService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DynamicToolRelevanceTest {
    private final IAgentRepository repository=mock(IAgentRepository.class);
    private final IWorkspaceAccessRepository access=mock(IWorkspaceAccessRepository.class);
    private final IToolVectorStore vectors=mock(IToolVectorStore.class);
    private final McpToolCatalogService service=new McpToolCatalogService();

    @Before public void setup() {
        ReflectionTestUtils.setField(service,"repository",repository);
        ReflectionTestUtils.setField(service,"workspaceAccess",access);
        ReflectionTestUtils.setField(service,"toolVectorStore",vectors);
        ReflectionTestUtils.setField(service,"mcpClientRegistry",mock(McpClientRegistry.class));
        MDC.put("userId","alice");MDC.put("agentId","a1");
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of("alice-tools"));
        when(access.ownsMcp("alice","alice-tools")).thenReturn(true);
        when(access.publicMcpOrigins("alice")).thenReturn(Map.of("alice-tools","platform"));
    }
    @After public void clear() { MDC.clear(); }

    private AiMcpToolCatalogVO tool(String name,String description) {
        return AiMcpToolCatalogVO.builder().mcpId("platform").toolName(name).toolDescriptionZh(description).enabled(1).build();
    }
    private List<String> find(String need) {
        return service.previewMatchedTools(need,"",Set.of()).stream().map(AiMcpToolCatalogVO::getToolName).toList();
    }

    @Test public void explicitNamesInSentencesWinWithoutEmbeddingsAndRetainRequestedOrder() {
        when(repository.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(
                tool("get-tickets","查询车票"),tool("get-station-code-of-citys","查询车站代码"),tool("calculate","数学计算")));
        assertEquals(List.of("get-station-code-of-citys","get-tickets"),find("先装载 get-station-code-of-citys，然后使用 get-tickets 查询"));
        verifyNoInteractions(vectors);
        when(access.agentMcpIds("alice","a1")).thenReturn(Set.of());
        assertTrue(find("get-tickets").isEmpty());
    }

    @Test public void nameSubstringIsNotAnExactIdentifier() {
        when(repository.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("get_paper","论文信息")));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(List.of());
        assertTrue(find("get_papers_archive").isEmpty());
        verify(vectors).searchOwned(anyString(),anySet(),anyInt(),anySet());
    }

    @Test public void confidentNoMatchCannotFallBackToCoincidentalCatalogWords() {
        when(repository.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("weather_forecast","天气预报")));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(List.of());
        assertTrue(find("查询天气预报").isEmpty());
    }

    @Test public void unavailableVectorIndexUsesOnlySupportedAuthorizedCapabilities() {
        when(repository.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(
                tool("weather_forecast","获取通用天气预报"),tool("nutrition_seed","下载食品数据，初始化营养数据库")));
        when(vectors.searchOwned(anyString(),anySet(),anyInt(),anySet())).thenReturn(null);
        assertEquals(List.of("weather_forecast"),find("查询天气预报"));
        assertTrue(find("创建CSV格式的数据文件并提供下载链接").isEmpty());
        verify(repository,never()).queryEnabledMcpToolCatalog();
    }

    @Test public void cancellationAndBudgetExhaustionBypassOtherwiseMatchingLocalFallback() {
        when(repository.queryMcpToolCatalogByMcpId("platform")).thenReturn(List.of(tool("weather_forecast","天气预报")));
        for (RuntimeException terminal : List.of(new java.util.concurrent.CancellationException("cancelled"),
                new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException("exhausted"))) {
            doThrow(new IllegalStateException("wrapped",terminal)).when(vectors)
                    .searchOwned(anyString(),anySet(),anyInt(),anySet());
            assertSame(terminal,assertThrows(RuntimeException.class,()->find("查询天气预报")));
        }
    }
}
