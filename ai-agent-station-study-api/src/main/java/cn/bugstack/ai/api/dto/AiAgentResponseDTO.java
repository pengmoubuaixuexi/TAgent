package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI智能体响应 DTO
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * @description AI智能体响应数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 描述
     */
    private String description;

    /**
     * 渠道类型(agent，chat_stream)
     */
    private String channel;

    /**
     * 执行策略(auto、flow)
     */
    private String strategy;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

}