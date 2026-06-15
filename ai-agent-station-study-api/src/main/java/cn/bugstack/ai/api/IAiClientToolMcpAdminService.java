package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.AiClientToolMcpQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientToolMcpRequestDTO;
import cn.bugstack.ai.api.dto.AiClientToolMcpResponseDTO;
import cn.bugstack.ai.api.response.Response;

import java.util.List;

/**
 * MCP客户端配置管理服务接口
 *
 * @author bugstack虫洞栈
 * @description MCP客户端配置管理服务接口
 */
public interface IAiClientToolMcpAdminService {

    /**
     * 创建MCP客户端配置
     * @param request MCP客户端配置请求对象
     * @return 操作结果
     */
    Response<Boolean> createAiClientToolMcp(AiClientToolMcpRequestDTO request);

    /**
     * 根据ID更新MCP客户端配置
     * @param request MCP客户端配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientToolMcpById(AiClientToolMcpRequestDTO request);

    /**
     * 根据MCP ID更新MCP客户端配置
     * @param request MCP客户端配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientToolMcpByMcpId(AiClientToolMcpRequestDTO request);

    /**
     * 根据ID删除MCP客户端配置
     * @param id 主键ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientToolMcpById(Long id);

    /**
     * 根据MCP ID删除MCP客户端配置
     * @param mcpId MCP ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientToolMcpByMcpId(String mcpId);

    /**
     * 根据ID查询MCP客户端配置
     * @param id 主键ID
     * @return MCP客户端配置对象
     */
    Response<AiClientToolMcpResponseDTO> queryAiClientToolMcpById(Long id);

    /**
     * 根据MCP ID查询MCP客户端配置
     * @param mcpId MCP ID
     * @return MCP客户端配置对象
     */
    Response<AiClientToolMcpResponseDTO> queryAiClientToolMcpByMcpId(String mcpId);

    /**
     * 查询所有MCP客户端配置
     * @return MCP客户端配置列表
     */
    Response<List<AiClientToolMcpResponseDTO>> queryAllAiClientToolMcps();

    /**
     * 根据状态查询MCP客户端配置
     * @param status 状态
     * @return MCP客户端配置列表
     */
    Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpsByStatus(Integer status);

    /**
     * 根据传输类型查询MCP客户端配置
     * @param transportType 传输类型
     * @return MCP客户端配置列表
     */
    Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpsByTransportType(String transportType);

    /**
     * 查询启用的MCP客户端配置
     * @return MCP客户端配置列表
     */
    Response<List<AiClientToolMcpResponseDTO>> queryEnabledAiClientToolMcps();

    /**
     * 根据查询条件查询MCP客户端配置列表
     * @param request 查询请求对象
     * @return MCP客户端配置列表
     */
    Response<List<AiClientToolMcpResponseDTO>> queryAiClientToolMcpList(AiClientToolMcpQueryRequestDTO request);

}