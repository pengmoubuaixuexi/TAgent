package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * LLM RAG Router 的完整决策结果（P0.1.4 + P2.3 12.2/12.7）。
 * <p>
 * 一次 LLM 调用同时输出：是否检索 + 推荐路径 + 子查询（分解）/ 变体（Fusion）。
 * HeuristicRagRouter 的 decide() 只填充 shouldRetrieve + path=HYBRID。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRouterDecision {

    public static final String PATH_DECOMPOSE = "DECOMPOSE";
    public static final String PATH_FUSION = "FUSION";
    public static final String PATH_HYBRID = "HYBRID";
    public static final String PATH_SIMPLE = "SIMPLE";

    private boolean shouldRetrieve;
    private String path;

    @Builder.Default
    private List<String> subQueries = Collections.emptyList();

    @Builder.Default
    private List<String> variants = Collections.emptyList();

    private String reason;

    public static RagRouterDecision skip(String reason) {
        return RagRouterDecision.builder()
                .shouldRetrieve(false).reason(reason).build();
    }

    public static RagRouterDecision retrieve() {
        return RagRouterDecision.builder()
                .shouldRetrieve(true).path(PATH_HYBRID).reason("default").build();
    }
}
