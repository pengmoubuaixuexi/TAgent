package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.AiClientAdvisorQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientAdvisorRequestDTO;
import cn.bugstack.ai.api.dto.AiClientAdvisorResponseDTO;
import cn.bugstack.ai.api.response.Response;

import java.util.List;

/**
 * 顾问配置管理服务接口
 *
 * @author bugstack虫洞栈
 * @description 顾问配置管理服务接口
 */
public interface IAiClientAdvisorAdminService {

    /**
     * 创建顾问配置
     * @param request 顾问配置请求对象
     * @return 操作结果
     */
    Response<Boolean> createAiClientAdvisor(AiClientAdvisorRequestDTO request);

    /**
     * 根据ID更新顾问配置
     * @param request 顾问配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientAdvisorById(AiClientAdvisorRequestDTO request);

    /**
     * 根据顾问ID更新顾问配置
     * @param request 顾问配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientAdvisorByAdvisorId(AiClientAdvisorRequestDTO request);

    /**
     * 根据ID删除顾问配置
     * @param id 主键ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientAdvisorById(Long id);

    /**
     * 根据顾问ID删除顾问配置
     * @param advisorId 顾问ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientAdvisorByAdvisorId(String advisorId);

    /**
     * 根据ID查询顾问配置
     * @param id 主键ID
     * @return 顾问配置对象
     */
    Response<AiClientAdvisorResponseDTO> queryAiClientAdvisorById(Long id);

    /**
     * 根据顾问ID查询顾问配置
     * @param advisorId 顾问ID
     * @return 顾问配置对象
     */
    Response<AiClientAdvisorResponseDTO> queryAiClientAdvisorByAdvisorId(String advisorId);

    /**
     * 查询所有启用的顾问配置
     * @return 顾问配置列表
     */
    Response<List<AiClientAdvisorResponseDTO>> queryEnabledAiClientAdvisors();

    /**
     * 根据状态查询顾问配置
     * @param status 状态
     * @return 顾问配置列表
     */
    Response<List<AiClientAdvisorResponseDTO>> queryAiClientAdvisorsByStatus(Integer status);

    /**
     * 根据顾问类型查询顾问配置
     * @param advisorType 顾问类型
     * @return 顾问配置列表
     */
    Response<List<AiClientAdvisorResponseDTO>> queryAiClientAdvisorsByType(String advisorType);

    /**
     * 根据条件查询顾问配置列表
     * @param request 查询条件
     * @return 顾问配置列表
     */
    Response<List<AiClientAdvisorResponseDTO>> queryAiClientAdvisorList(AiClientAdvisorQueryRequestDTO request);

    /**
     * 查询所有顾问配置
     * @return 顾问配置列表
     */
    Response<List<AiClientAdvisorResponseDTO>> queryAllAiClientAdvisors();

}