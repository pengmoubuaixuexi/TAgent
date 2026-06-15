package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiClientConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI客户端统一关联配置表 DAO
 * @author bugstack虫洞栈
 * @description AI客户端统一关联配置表数据访问对象
 */
@Mapper
public interface IAiClientConfigDao {

    /**
     * 插入AI客户端配置
     * @param aiClientConfig AI客户端配置对象
     * @return 影响行数
     */
    int insert(AiClientConfig aiClientConfig);

    /**
     * 根据ID更新AI客户端配置
     * @param aiClientConfig AI客户端配置对象
     * @return 影响行数
     */
    int updateById(AiClientConfig aiClientConfig);

    /**
     * 根据源ID更新AI客户端配置
     * @param aiClientConfig AI客户端配置对象
     * @return 影响行数
     */
    int updateBySourceId(AiClientConfig aiClientConfig);

    /**
     * 根据ID删除AI客户端配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据源ID删除AI客户端配置
     * @param sourceId 源ID
     * @return 影响行数
     */
    int deleteBySourceId(String sourceId);

    /**
     * 根据ID查询AI客户端配置
     * @param id 主键ID
     * @return AI客户端配置对象
     */
    AiClientConfig queryById(Long id);

    /**
     * 根据源ID查询AI客户端配置
     * @param sourceId 源ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryBySourceId(String sourceId);

    /**
     * 根据目标ID查询AI客户端配置
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryByTargetId(String targetId);

    /**
     * 根据源类型和源ID查询AI客户端配置
     * @param sourceType 源类型
     * @param sourceId 源ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryBySourceTypeAndId(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /**
     * 根据目标类型和目标ID查询AI客户端配置
     * @param targetType 目标类型
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryByTargetTypeAndId(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /**
     * 根据源类型、源ID、目标类型、目标ID查询AI客户端配置
     * @param sourceType 源类型
     * @param sourceId 源ID
     * @param targetType 目标类型
     * @param targetId 目标ID
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryByConditions(@Param("sourceType") String sourceType, 
                                          @Param("sourceId") String sourceId,
                                          @Param("targetType") String targetType, 
                                          @Param("targetId") String targetId);

    /**
     * 查询启用状态的AI客户端配置
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryEnabledConfigs();

    /**
     * 查询所有AI客户端配置
     * @return AI客户端配置对象列表
     */
    List<AiClientConfig> queryAll();

}