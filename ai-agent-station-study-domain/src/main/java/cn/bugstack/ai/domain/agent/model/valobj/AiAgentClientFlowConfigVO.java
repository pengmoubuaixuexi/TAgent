package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端配置
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/27 17:18
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentClientFlowConfigVO {

    /**
     * 客户端ID
     */
    private String clientId;

    /**
     * 客户端名称
     */
    private String clientName;

    /**
     * 客户端枚举
     */
    private String clientType;

    /**
     * 序列号(执行顺序)
     */
    private Integer sequence;

    /**
     * 执行步骤提示词
     */
    private String stepPrompt;

}
