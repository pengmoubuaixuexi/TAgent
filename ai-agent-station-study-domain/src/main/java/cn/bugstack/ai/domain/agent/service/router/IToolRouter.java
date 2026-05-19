package cn.bugstack.ai.domain.agent.service.router;

import java.util.List;

/**
 * 工具路由（P0.1.5）。
 * <p>
 * 给定 query 和候选工具列表，返回最相关的 top-K 工具。<b>当前为 shadow mode</b>：只采集"如果按
 * 路由建议，会用哪些工具"的信号，<b>不</b>实际过滤 ChatClient 上已经 bound 的工具集（受 Spring AI 1.0
 * SyncMcpToolCallbackProvider 在 build 时绑定的限制）。
 * <p>
 * 等到 P1 阶段的"按 query 重建 ChatClient" 或 Spring AI 提供 per-request tool override 后，
 * 直接换接口实现就能让 prompt token 立刻降下来。
 */
public interface IToolRouter {

    /** 候选项；数据从 ai_client_tool_mcp 来 */
    record Candidate(String mcpId, String mcpName, String description) {}

    /** 路由结果；keep=true 表示推荐保留这个工具 */
    record Decision(String mcpId, double score, boolean keep) {}

    /**
     * @param query      用户 query
     * @param candidates 当前 agent 配置的全部 MCP 工具
     * @param topK       要保留的工具数；候选不足 topK 时按实际数返回
     * @return 按 score 倒序的决策列表（长度 == candidates.size，分 keep/不 keep）
     */
    List<Decision> route(String query, List<Candidate> candidates, int topK);
}
