package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI智能体拖拉拽配置关系表
 * @author bugstack虫洞栈
 * @description AI智能体拖拉拽配置关系表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentDrawRelations {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 配置ID（关联ai_agent_draw_config）
     */
    private String configId;

    /**
     * 源节点ID
     */
    private String sourceNodeId;

    /**
     * 源类型（model、client、agent、prompt、advisor、tool_mcp）
     */
    private String sourceType;

    /**
     * 源引用ID（实际的资源ID）
     */
    private String sourceRefId;

    /**
     * 目标节点ID
     */
    private String targetNodeId;

    /**
     * 目标类型（model、client、agent、prompt、advisor、tool_mcp）
     */
    private String targetType;

    /**
     * 目标引用ID（实际的资源ID）
     */
    private String targetRefId;

    /**
     * 关系类型（default、conditional、loop等）
     */
    private String relationType;

    /**
     * 扩展参数（JSON格式）
     */
    private String extParam;

    /**
     * 关系序号（用于排序）
     */
    private Integer sequence;

    /**
     * 同步状态(0:未同步,1:已同步到ai_client_config)
     */
    private Integer syncStatus;

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