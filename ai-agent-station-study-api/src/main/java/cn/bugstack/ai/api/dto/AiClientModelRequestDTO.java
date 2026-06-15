package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI客户端模型配置请求 DTO
 *
 * @author bugstack虫洞栈
 * @description AI客户端模型配置请求数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientModelRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增主键ID（更新时使用）
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
     * 状态：0-禁用，1-启用
     */
    private Integer status;

}