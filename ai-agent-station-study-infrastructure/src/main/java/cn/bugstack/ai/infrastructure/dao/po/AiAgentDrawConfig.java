package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI智能体拖拉拽配置主表
 * @author bugstack虫洞栈
 * @description AI智能体拖拉拽配置主表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentDrawConfig {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 配置ID（唯一标识）
     */
    private String configId;

    /**
     * 配置名称
     */
    private String configName;

    /**
     * 配置描述
     */
    private String description;

    /**
     * 关联的智能体ID（来自ai_agent表）
     */
    private String agentId;

    /**
     * 完整的拖拉拽配置JSON数据（包含nodes和edges）
     */
    private String configData;

    /**
     * 配置版本号
     */
    private Integer version;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 更新人
     */
    private String updateBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}