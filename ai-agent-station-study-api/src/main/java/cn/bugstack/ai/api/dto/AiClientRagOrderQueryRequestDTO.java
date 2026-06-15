package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库配置查询请求 DTO
 *
 * @author bugstack虫洞栈
 * @description 知识库配置查询请求数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientRagOrderQueryRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识库ID
     */
    private String ragId;

    /**
     * 知识库名称（模糊查询）
     */
    private String ragName;

    /**
     * 知识标签
     */
    private String knowledgeTag;

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