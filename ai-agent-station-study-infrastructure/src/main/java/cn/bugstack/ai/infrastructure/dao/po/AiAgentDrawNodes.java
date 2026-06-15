package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI智能体拖拉拽配置节点表
 * @author bugstack虫洞栈
 * @description AI智能体拖拉拽配置节点表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentDrawNodes {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 配置ID（关联ai_agent_draw_config）
     */
    private String configId;

    /**
     * 节点ID（在配置中的唯一标识）
     */
    private String nodeId;

    /**
     * 节点类型（start、client、agent、task、advisor、prompt、model、tool-mcp、end等）
     */
    private String nodeType;

    /**
     * 节点标题
     */
    private String nodeTitle;

    /**
     * X坐标位置
     */
    private BigDecimal positionX;

    /**
     * Y坐标位置
     */
    private BigDecimal positionY;

    /**
     * 节点数据（JSON格式，包含inputs、outputs、inputsValues等）
     */
    private String nodeData;

    /**
     * 引用的实际资源ID（如agent_id、client_id等）
     */
    private String refId;

    /**
     * 引用的资源类型（agent、client、model、prompt、advisor、tool_mcp）
     */
    private String refType;

    /**
     * 节点序号（用于排序）
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