package cn.bugstack.ai.api;

import cn.bugstack.ai.api.dto.AiClientRagOrderQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientRagOrderRequestDTO;
import cn.bugstack.ai.api.dto.AiClientRagOrderResponseDTO;
import cn.bugstack.ai.api.response.Response;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库配置管理服务接口
 *
 * @author bugstack虫洞栈
 * @description 知识库配置管理服务接口
 */
public interface IAiClientRagOrderAdminService {

    /**
     * 创建知识库配置
     * @param request 知识库配置请求对象
     * @return 操作结果
     */
    Response<Boolean> createAiClientRagOrder(AiClientRagOrderRequestDTO request);

    /**
     * 根据ID更新知识库配置
     * @param request 知识库配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientRagOrderById(AiClientRagOrderRequestDTO request);

    /**
     * 根据知识库ID更新知识库配置
     * @param request 知识库配置请求对象
     * @return 操作结果
     */
    Response<Boolean> updateAiClientRagOrderByRagId(AiClientRagOrderRequestDTO request);

    /**
     * 根据ID删除知识库配置
     * @param id 主键ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientRagOrderById(Long id);

    /**
     * 根据知识库ID删除知识库配置
     * @param ragId 知识库ID
     * @return 操作结果
     */
    Response<Boolean> deleteAiClientRagOrderByRagId(String ragId);

    /**
     * 根据ID查询知识库配置
     * @param id 主键ID
     * @return 知识库配置对象
     */
    Response<AiClientRagOrderResponseDTO> queryAiClientRagOrderById(Long id);

    /**
     * 根据知识库ID查询知识库配置
     * @param ragId 知识库ID
     * @return 知识库配置对象
     */
    Response<AiClientRagOrderResponseDTO> queryAiClientRagOrderByRagId(String ragId);

    /**
     * 查询所有启用的知识库配置
     * @return 知识库配置列表
     */
    Response<List<AiClientRagOrderResponseDTO>> queryEnabledAiClientRagOrders();

    /**
     * 根据知识标签查询知识库配置
     * @param knowledgeTag 知识标签
     * @return 知识库配置列表
     */
    Response<List<AiClientRagOrderResponseDTO>> queryAiClientRagOrdersByKnowledgeTag(String knowledgeTag);

    /**
     * 根据状态查询知识库配置
     * @param status 状态
     * @return 知识库配置列表
     */
    Response<List<AiClientRagOrderResponseDTO>> queryAiClientRagOrdersByStatus(Integer status);

    /**
     * 分页查询知识库配置列表
     * @param request 查询请求对象
     * @return 知识库配置列表
     */
    Response<List<AiClientRagOrderResponseDTO>> queryAiClientRagOrderList(AiClientRagOrderQueryRequestDTO request);

    /**
     * 查询所有知识库配置
     * @return 知识库配置列表
     */
    Response<List<AiClientRagOrderResponseDTO>> queryAllAiClientRagOrders();

    /**
     * 上传知识库文件
     * @param name 知识库名称
     * @param tag 知识库标签
     * @param files 上传的文件列表
     * @return 操作结果
     */
    Response<Boolean> uploadRagFile(String name, String tag, List<MultipartFile> files);

}