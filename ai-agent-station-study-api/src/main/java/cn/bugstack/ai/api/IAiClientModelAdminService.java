package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.AiClientModelQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientModelRequestDTO;
import cn.bugstack.ai.api.dto.AiClientModelResponseDTO;
import cn.bugstack.ai.api.response.Response;

import java.util.List;

/**
 * AI客户端模型管理服务接口
 *
 * @author bugstack虫洞栈
 * @description AI客户端模型配置管理服务接口
 */
public interface IAiClientModelAdminService {

    /**
     * 创建AI客户端模型配置
     * @param request AI客户端模型配置请求对象
     * @return 操作结果
     */
    Response<Boolean> createAiClientModel(AiClientModelRequestDTO request);

    /**
     * 根据ID更新AI客户端模型配置
     * @param request AI客户端模型配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientModelById(AiClientModelRequestDTO request);

    /**
     * 根据模型ID更新AI客户端模型配置
     * @param request AI客户端模型配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientModelByModelId(AiClientModelRequestDTO request);

    /**
     * 根据ID删除AI客户端模型配置
     * @param id 主键ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientModelById(Long id);

    /**
     * 根据模型ID删除AI客户端模型配置
     * @param modelId 模型ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientModelByModelId(String modelId);

    /**
     * 根据ID查询AI客户端模型配置
     * @param id 主键ID
     * @return AI客户端模型配置对象
     */
    Response<AiClientModelResponseDTO> queryAiClientModelById(Long id);

    /**
     * 根据模型ID查询AI客户端模型配置
     * @param modelId 模型ID
     * @return AI客户端模型配置对象
     */
    Response<AiClientModelResponseDTO> queryAiClientModelByModelId(String modelId);

    /**
     * 根据API配置ID查询AI客户端模型配置列表
     * @param apiId API配置ID
     * @return AI客户端模型配置列表
     */
    Response<List<AiClientModelResponseDTO>> queryAiClientModelsByApiId(String apiId);

    /**
     * 根据模型类型查询AI客户端模型配置列表
     * @param modelType 模型类型
     * @return AI客户端模型配置列表
     */
    Response<List<AiClientModelResponseDTO>> queryAiClientModelsByModelType(String modelType);

    /**
     * 查询所有启用的AI客户端模型配置
     * @return AI客户端模型配置列表
     */
    Response<List<AiClientModelResponseDTO>> queryEnabledAiClientModels();

    /**
     * 根据条件查询AI客户端模型配置列表
     * @param request 查询条件
     * @return AI客户端模型配置列表
     */
    Response<List<AiClientModelResponseDTO>> queryAiClientModelList(AiClientModelQueryRequestDTO request);

    /**
     * 查询所有AI客户端模型配置
     * @return AI客户端模型配置列表
     */
    Response<List<AiClientModelResponseDTO>> queryAllAiClientModels();

}