package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * MCP客户端配置查询请求 DTO
 *
 * @author bugstack虫洞栈
 * @description MCP客户端配置查询请求数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientToolMcpQueryRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * MCP ID
     */
    private String mcpId;

    /**
     * MCP名称（模糊查询）
     */
    private String mcpName;

    /**
     * 传输类型(sse/stdio)
     */
    private String transportType;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * 页码（分页查询）
     */
    private Integer pageNum;

    /**
     * 每页大小（分页查询）
     */
    private Integer pageSize;

}