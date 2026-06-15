package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiClientModel;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 聊天模型配置表 DAO
 * @author bugstack虫洞栈
 * @description 聊天模型配置表数据访问对象
 */
@Mapper
public interface IAiClientModelDao {

    /**
     * 插入聊天模型配置
     * @param aiClientModel 聊天模型配置对象
     * @return 影响行数
     */
    int insert(AiClientModel aiClientModel);

    /**
     * 根据ID更新聊天模型配置
     * @param aiClientModel 聊天模型配置对象
     * @return 影响行数
     */
    int updateById(AiClientModel aiClientModel);

    /**
     * 根据模型ID更新聊天模型配置
     * @param aiClientModel 聊天模型配置对象
     * @return 影响行数
     */
    int updateByModelId(AiClientModel aiClientModel);

    /**
     * 根据ID删除聊天模型配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据模型ID删除聊天模型配置
     * @param modelId 模型ID
     * @return 影响行数
     */
    int deleteByModelId(String modelId);

    /**
     * 根据ID查询聊天模型配置
     * @param id 主键ID
     * @return 聊天模型配置对象
     */
    AiClientModel queryById(Long id);

    /**
     * 根据模型ID查询聊天模型配置
     * @param modelId 模型ID
     * @return 聊天模型配置对象
     */
    AiClientModel queryByModelId(String modelId);

    /**
     * 根据API配置ID查询聊天模型配置
     * @param apiId API配置ID
     * @return 聊天模型配置列表
     */
    List<AiClientModel> queryByApiId(String apiId);

    /**
     * 根据模型类型查询聊天模型配置
     * @param modelType 模型类型
     * @return 聊天模型配置列表
     */
    List<AiClientModel> queryByModelType(String modelType);

    /**
     * 查询所有启用的聊天模型配置
     * @return 聊天模型配置列表
     */
    List<AiClientModel> queryEnabledModels();

    /**
     * 查询所有聊天模型配置
     * @return 聊天模型配置列表
     */
    List<AiClientModel> queryAll();

}