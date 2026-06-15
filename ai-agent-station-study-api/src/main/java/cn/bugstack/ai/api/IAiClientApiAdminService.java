package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.AiClientApiQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientApiRequestDTO;
import cn.bugstack.ai.api.dto.AiClientApiResponseDTO;
import cn.bugstack.ai.api.response.Response;

import java.util.List;

/**
 * AI客户端API配置管理服务接口
 *
 * @author bugstack虫洞栈
 * @description AI客户端API配置管理服务接口
 */
public interface IAiClientApiAdminService {

    /**
     * 创建AI客户端API配置
     * @param request AI客户端API配置请求对象
     * @return 操作结果
     */
    Response<Boolean> createAiClientApi(AiClientApiRequestDTO request);

    /**
     * 根据ID更新AI客户端API配置
     * @param request AI客户端API配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientApiById(AiClientApiRequestDTO request);

    /**
     * 根据API ID更新AI客户端API配置
     * @param request AI客户端API配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientApiByApiId(AiClientApiRequestDTO request);

    /**
     * 根据ID删除AI客户端API配置
     * @param id 主键ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientApiById(Long id);

    /**
     * 根据API ID删除AI客户端API配置
     * @param apiId API ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientApiByApiId(String apiId);

    /**
     * 根据ID查询AI客户端API配置
     * @param id 主键ID
     * @return AI客户端API配置对象
     */
    Response<AiClientApiResponseDTO> queryAiClientApiById(Long id);

    /**
     * 根据API ID查询AI客户端API配置
     * @param apiId API ID
     * @return AI客户端API配置对象
     */
    Response<AiClientApiResponseDTO> queryAiClientApiByApiId(String apiId);

    /**
     * 查询所有启用的AI客户端API配置
     * @return AI客户端API配置列表
     */
    Response<List<AiClientApiResponseDTO>> queryEnabledAiClientApis();

    /**
     * 分页查询AI客户端API配置列表
     * @param request 查询请求对象
     * @return AI客户端API配置列表
     */
    Response<List<AiClientApiResponseDTO>> queryAiClientApiList(AiClientApiQueryRequestDTO request);

    /**
     * 查询所有AI客户端API配置
     * @return AI客户端API配置列表
     */
    Response<List<AiClientApiResponseDTO>> queryAllAiClientApis();

}