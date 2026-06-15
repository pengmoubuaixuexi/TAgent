package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI客户端模型配置响应 DTO
 *
 * @author bugstack虫洞栈
 * @description AI客户端模型配置响应数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientModelResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增主键ID
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

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}