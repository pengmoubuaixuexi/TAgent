package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI智能体拖拉拽配置请求DTO
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/1/20 10:00
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentDrawConfigRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
     * 关联的智能体ID
     */
    private String agentId;

    /**
     * 完整的拖拉拽配置JSON数据（包含nodes和edges）
     */
    private String configData;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 更新人
     */
    private String updateBy;

}