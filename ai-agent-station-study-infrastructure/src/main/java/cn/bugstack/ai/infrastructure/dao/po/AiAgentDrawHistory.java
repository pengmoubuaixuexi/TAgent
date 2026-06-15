package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI智能体拖拉拽配置历史表
 * @author bugstack虫洞栈
 * @description AI智能体拖拉拽配置历史表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentDrawHistory {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 配置ID（关联ai_agent_draw_config）
     */
    private String configId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 历史配置JSON数据
     */
    private String configData;

    /**
     * 变更类型（create、update、delete）
     */
    private String changeType;

    /**
     * 变更描述
     */
    private String changeDesc;

    /**
     * 变更人
     */
    private String changeBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}