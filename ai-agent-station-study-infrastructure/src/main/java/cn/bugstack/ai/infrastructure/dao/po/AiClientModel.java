package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天模型配置表
 * @author bugstack虫洞栈
 * @description 聊天模型配置表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientModel {

    /**
     * 自增主键ID
     */
    private Long id;

    /**
     * 全局唯一模型ID
     */
    private String modelId;

    /**
     * 关联的API配置ID
     */
    private String apiId;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 模型类型：openai、deepseek、claude
     */
    private String modelType;

    /**
     * 模型用途
     */
    private String modelUsage;

    /**
     * 档位（P0.1.3 Model Router）：small / medium / large
     * - small：分类、解析、摘要等轻量任务（haiku / mini / nano）
     * - medium：常规对话、QA（sonnet）
     * - large：深度推理、复杂规划（opus / gpt-4o / gpt-4-turbo）
     */
    private String tier;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}