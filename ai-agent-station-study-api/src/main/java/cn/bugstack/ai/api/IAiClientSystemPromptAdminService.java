package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.AiClientSystemPromptQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientSystemPromptRequestDTO;
import cn.bugstack.ai.api.dto.AiClientSystemPromptResponseDTO;
import cn.bugstack.ai.api.response.Response;

import java.util.List;

/**
 * 系统提示词配置管理服务接口
 *
 * @author bugstack虫洞栈
 * @description 系统提示词配置管理服务接口
 */
public interface IAiClientSystemPromptAdminService {

    /**
     * 创建系统提示词配置
     * @param request 系统提示词配置请求对象
     * @return 操作结果
     */
    Response<Boolean> createAiClientSystemPrompt(AiClientSystemPromptRequestDTO request);

    /**
     * 根据ID更新系统提示词配置
     * @param request 系统提示词配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientSystemPromptById(AiClientSystemPromptRequestDTO request);

    /**
     * 根据提示词ID更新系统提示词配置
     * @param request 系统提示词配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientSystemPromptByPromptId(AiClientSystemPromptRequestDTO request);

    /**
     * 根据ID删除系统提示词配置
     * @param id 主键ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientSystemPromptById(Long id);

    /**
     * 根据提示词ID删除系统提示词配置
     * @param promptId 提示词ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientSystemPromptByPromptId(String promptId);

    /**
     * 根据ID查询系统提示词配置
     * @param id 主键ID
     * @return 系统提示词配置信息
     */
    Response<AiClientSystemPromptResponseDTO> queryAiClientSystemPromptById(Long id);

    /**
     * 根据提示词ID查询系统提示词配置
     * @param promptId 提示词ID
     * @return 系统提示词配置信息
     */
    Response<AiClientSystemPromptResponseDTO> queryAiClientSystemPromptByPromptId(String promptId);

    /**
     * 查询所有系统提示词配置
     * @return 系统提示词配置列表
     */
    Response<List<AiClientSystemPromptResponseDTO>> queryAllAiClientSystemPrompts();

    /**
     * 查询启用的系统提示词配置
     * @return 启用的系统提示词配置列表
     */
    Response<List<AiClientSystemPromptResponseDTO>> queryEnabledAiClientSystemPrompts();

    /**
     * 根据提示词名称查询系统提示词配置
     * @param promptName 提示词名称
     * @return 系统提示词配置列表
     */
    Response<List<AiClientSystemPromptResponseDTO>> queryAiClientSystemPromptsByPromptName(String promptName);

    /**
     * 根据条件查询系统提示词配置列表
     * @param request 查询请求对象
     * @return 系统提示词配置列表
     */
    Response<List<AiClientSystemPromptResponseDTO>> queryAiClientSystemPromptList(AiClientSystemPromptQueryRequestDTO request);

}