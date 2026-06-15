package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * MCP客户端配置请求 DTO
 *
 * @author bugstack虫洞栈
 * @description MCP客户端配置请求数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientToolMcpRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（更新时使用）
     */
    private Long id;

    /**
     * MCP ID
     */
    private String mcpId;

    /**
     * MCP名称
     */
    private String mcpName;

    /**
     * 传输类型(sse/stdio)
     */
    private String transportType;

    /**
     * 传输配置(sse/stdio)
     */
    private String transportConfig;

    /**
     * 请求超时时间(分钟)
     */
    private Integer requestTimeout;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

}