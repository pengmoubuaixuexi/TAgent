package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统提示词配置查询请求 DTO
 *
 * @author bugstack虫洞栈
 * @description 系统提示词配置查询请求数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientSystemPromptQueryRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 提示词ID
     */
    private String promptId;

    /**
     * 提示词名称（模糊查询）
     */
    private String promptName;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * 页码（分页查询）
     */
    private Integer pageNum;

    /**
     * 页大小（分页查询）
     */
    private Integer pageSize;

}