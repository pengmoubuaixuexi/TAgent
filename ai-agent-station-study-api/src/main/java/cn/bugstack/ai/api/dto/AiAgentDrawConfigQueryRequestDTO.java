package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI智能体拖拉拽配置查询请求 DTO
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/10/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentDrawConfigQueryRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 配置ID（唯一标识）
     */
    private String configId;

    /**
     * 配置名称（模糊查询）
     */
    private String configName;

    /**
     * 关联的智能体ID
     */
    private String agentId;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * 页码（从1开始）
     */
    private Integer pageNum;

    /**
     * 每页大小
     */
    private Integer pageSize;
}