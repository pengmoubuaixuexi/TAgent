package cn.bugstack.ai.test.domain;

import cn.bugstack.ai.infrastructure.adapter.repository.cache.ToolVectorStore;
import org.junit.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline replay of actual EVAL candidate scores; no provider or database calls. */
public class ToolVectorStoreRelevanceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);
    private final ToolVectorStore store = new ToolVectorStore(jdbc, embedding, .30, .10);

    private List<String> search(String need, Map<String,Object>... rows) {
        when(embedding.embed(need)).thenReturn(new float[]{1,0});
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(rows));
        var result=store.searchOwned(need,Set.of(),2,Set.of("allowed"));
        return result==null?null:result.stream().map(t->t.getToolName()).toList();
    }

    private Map<String,Object> row(String name, double score, String description) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("mcp_id","allowed");row.put("tool_name",name);row.put("mcp_name","connection");
        row.put("description_zh",description);row.put("intent_zh","");row.put("similarity",score);
        return row;
    }

    @Test public void budgetCalculationDoesNotLoadWalkingDirectionsToFillTopTwo() {
        assertEquals(List.of("calculate"),search("数学计算：计算预算各项金额和百分比",
                row("calculate",.526,"计算数学表达式、简化代数式或求符号导数，支持算术、三角、统计、矩阵、复数、单位换算和组合数学等运算。"),
                row("maps_direction_walking",.441,"规划100公里以内的步行通勤路线，返回方案数据。")));
    }

    @Test public void dddTutorialNeedRejectsDrivingAndPaperOnlySearch() {
        assertTrue(search("搜索互联网上关于DDD领域驱动设计的入门教程、书籍、视频课程等学习资源",
                row("maps_direction_driving",.561,"根据起终点经纬度规划小客车或轿车驾车出行方案，返回通勤数据。"),
                row("search_papers",.544,"在 arXiv 上根据关键词搜索学术论文，快速找到相关研究文献。")).isEmpty());
    }

    @Test public void csvCreationDoesNotBecomeNutritionDownloadOrPaperMetadata() {
        assertTrue(search("创建CSV格式的数据文件并提供下载链接",
                row("nutrition_seed",.501,"为本地数据库初始化超过32.6万条食品数据，后台下载约60MB，可重复调用以查看进度，已初始化则无操作。"),
                row("get_paper",.469,"根据 arXiv 论文 ID 获取完整详细信息，包括标题、作者、摘要等。")).isEmpty());
    }

    @Test public void interactiveQuestionDoesNotBecomeImageGenerationOrWebSearch() {
        assertTrue(search("向用户提问并获取用户回答的交互式对话工具",
                row("generate_image",.534,"根据用户描述生成图片。"),
                row("webSearch",.522,"搜索实时网页信息，根据用户提问获取最新的网络内容。")).isEmpty());
    }

    @Test public void lowerRankedRelevantToolIsConsideredAfterUnrelatedTopHit() {
        assertEquals(List.of("weather_forecast"), search("查询北京未来三天的天气预报",
                row("maps_direction_walking",.61,"规划步行通勤路线，返回方案数据。"),
                row("weather_forecast",.56,"根据经纬度获取通用天气预报，支持逐小时和逐日数据，自动选择最佳预报模型。")));
    }

    @Test public void transitCapabilityAndStrongCrossLanguageParaphraseStillMatch() {
        assertEquals(List.of("maps_direction_transit_integrated"),search("规划两地之间的公共交通路线",
                row("maps_direction_transit_integrated",.57,"规划包含火车、公交、地铁等综合公共交通通勤方案，跨城需传起点和终点城市。")));
        assertEquals(List.of("weather_forecast"),search("Will I need an umbrella in Paris tomorrow?",
                row("weather_forecast",.72,"根据经纬度获取通用天气预报，支持逐小时和逐日数据。")));
    }

    @Test public void expandedIntentCannotInventAnUnsupportedCapability() {
        var paper=row("get_paper",.54,"根据 arXiv 论文 ID 获取完整详细信息，包括标题、作者、摘要等。");
        paper.put("intent_zh","创建CSV格式的数据文件并提供下载链接");
        assertTrue(search("创建CSV格式的数据文件并提供下载链接",paper).isEmpty());
    }

    @Test public void absentIndexSignalsFallbackButRejectedCandidatesDoNot() {
        assertNull(search("天气预报"));
        assertTrue(search("天气预报",row("calculate",.4,"数学计算表达式")).isEmpty());
    }

    @Test public void embeddingCancellationAndBudgetExhaustionNeverBecomeFallback() {
        for (RuntimeException terminal : List.of(new java.util.concurrent.CancellationException("cancelled"),
                new cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException("exhausted"))) {
            doThrow(new IllegalStateException("wrapped",terminal)).when(embedding).embed("天气预报");
            assertSame(terminal,assertThrows(RuntimeException.class,
                    ()->store.searchOwned("天气预报",Set.of(),2,Set.of("allowed"))));
        }
        verifyNoInteractions(jdbc);
    }
}
