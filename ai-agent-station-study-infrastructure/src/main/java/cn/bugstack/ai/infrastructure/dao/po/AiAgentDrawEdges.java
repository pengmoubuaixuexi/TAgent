package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI智能体拖拉拽配置连线表
 * @author bugstack虫洞栈
 * @description AI智能体拖拉拽配置连线表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentDrawEdges {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 配置ID（关联ai_agent_draw_config）
     */
    private String configId;

    /**
     * 连线ID（自动生成的唯一标识）
     */
    private String edgeId;

    /**
     * 源节点ID
     */
    private String sourceNodeId;

    /**
     * 目标节点ID
     */
    private String targetNodeId;

    /**
     * 源端口ID（可选）
     */
    private String sourcePortId;

    /**
     * 目标端口ID（可选）
     */
    private String targetPortId;

    /**
     * 连线类型（default、conditional等）
     */
    private String edgeType;

    /**
     * 连线数据（JSON格式，扩展信息）
     */
    private String edgeData;

    /**
     * 连线序号（用于排序）
     */
    private Integer sequence;

    /**
     * 状态(0:禁用,1:启用)
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