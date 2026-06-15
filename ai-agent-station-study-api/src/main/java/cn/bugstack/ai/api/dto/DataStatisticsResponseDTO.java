package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据统计响应 DTO
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * @description 数据统计响应数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DataStatisticsResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 活跃代理数量
     */
    private Long activeAgentCount;

    /**
     * 客户端数量
     */
    private Long clientCount;

    /**
     * MCP工具数量
     */
    private Long mcpToolCount;

    /**
     * 系统提示词数量
     */
    private Long systemPromptCount;

    /**
     * 知识库数量
     */
    private Long ragOrderCount;

    /**
     * 顾问配置数量
     */
    private Long advisorCount;

    /**
     * 模型配置数量
     */
    private Long modelCount;

    /**
     * 今日请求数量（模拟数据，实际项目中需要从日志或统计表获取）
     */
    private Long todayRequestCount;

    /**
     * 成功率（模拟数据，实际项目中需要从日志或统计表计算）
     */
    private Double successRate;

    /**
     * 运行中任务数量（模拟数据，实际项目中需要从任务调度表获取）
     */
    private Long runningTaskCount;
}