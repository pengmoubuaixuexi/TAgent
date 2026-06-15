package cn.bugstack.ai.domain.agent.service.router;

import java.util.List;

/**
 * 统一路由的决策结果。
 *
 * <p>选中的 agent 仍由自身策略决定执行方式。{@code missingToolDescs} 是路由对"本次 query 可能需要、
 * 但该 agent 当前未挂载"的工具能力的中文自然语言描述——它们<b>不是</b>工具名，仅用于后端做 PgVector
 * 语义匹配补挂工具。可以是<b>多条</b>（一次 query 可能缺多类能力，如既要联网搜索又要查天气）：
 * 每条 need 各自 embedding 取 top-k，最后<b>并集</b>去重作为要补的工具集。空列表表示不需要补充工具。
 */
public record RouteDecision(
        String agentId,
        List<String> missingToolDescs,
        Double confidence,
        String rawResponse
) {

    public RouteDecision {
        missingToolDescs = missingToolDescs == null ? List.of() : List.copyOf(missingToolDescs);
    }

    public static RouteDecision empty(String rawResponse) {
        return new RouteDecision(null, List.of(), null, rawResponse);
    }

    /** 是否有需要补挂的工具能力描述。 */
    public boolean hasMissingToolDesc() {
        return missingToolDescs != null && !missingToolDescs.isEmpty();
    }

    /** 多条 need 用换行连成单串，喂给沿用单字段透传的 {@code ExecuteCommandEntity.dynamicMissingToolDesc}。 */
    public String missingToolDescJoined() {
        return missingToolDescs == null || missingToolDescs.isEmpty()
                ? null : String.join("\n", missingToolDescs);
    }
}
