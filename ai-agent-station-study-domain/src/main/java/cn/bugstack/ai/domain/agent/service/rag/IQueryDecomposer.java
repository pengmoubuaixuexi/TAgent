package cn.bugstack.ai.domain.agent.service.rag;

import java.util.List;

/**
 * P2.3 12.2 Query Decomposition：多跳复杂问题拆成多个子查询，分别检索后合并。
 * <p>
 * 举例："小明是谁，他发明了什么？" → ["小明是谁", "小明发明了什么"]
 */
public interface IQueryDecomposer {

    /**
     * 判断是否需要分解（复杂度门槛）。
     */
    boolean shouldDecompose(String query);

    /**
     * 将查询分解为子查询列表。失败返回原查询的单元素列表。
     */
    List<String> decompose(String query);
}
