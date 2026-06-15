package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentDrawConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI智能体拖拉拽配置主表 DAO
 * @author bugstack虫洞栈
 * @description AI智能体拖拉拽配置主表数据访问对象
 */
@Mapper
public interface IAiAgentDrawConfigDao {

    /**
     * 插入拖拉拽配置
     * @param aiAgentDrawConfig 拖拉拽配置对象
     * @return 影响行数
     */
    int insert(AiAgentDrawConfig aiAgentDrawConfig);

    /**
     * 根据ID更新拖拉拽配置
     * @param aiAgentDrawConfig 拖拉拽配置对象
     * @return 影响行数
     */
    int updateById(AiAgentDrawConfig aiAgentDrawConfig);

    /**
     * 根据配置ID更新拖拉拽配置
     * @param aiAgentDrawConfig 拖拉拽配置对象
     * @return 影响行数
     */
    int updateByConfigId(AiAgentDrawConfig aiAgentDrawConfig);

    /**
     * 根据ID删除拖拉拽配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据配置ID删除拖拉拽配置
     * @param configId 配置ID
     * @return 影响行数
     */
    int deleteByConfigId(@Param("configId") String configId);

    /**
     * 根据ID查询拖拉拽配置
     * @param id 主键ID
     * @return 拖拉拽配置对象
     */
    AiAgentDrawConfig queryById(Long id);

    /**
     * 根据配置ID查询拖拉拽配置
     * @param configId 配置ID
     * @return 拖拉拽配置对象
     */
    AiAgentDrawConfig queryByConfigId(@Param("configId") String configId);

    /**
     * 根据智能体ID查询拖拉拽配置
     * @param agentId 智能体ID
     * @return 拖拉拽配置对象
     */
    AiAgentDrawConfig queryByAgentId(@Param("agentId") String agentId);

    /**
     * 查询启用状态的拖拉拽配置列表
     * @return 拖拉拽配置列表
     */
    List<AiAgentDrawConfig> queryEnabledConfigs();

    /**
     * 根据配置名称模糊查询拖拉拽配置列表
     * @param configName 配置名称
     * @return 拖拉拽配置列表
     */
    List<AiAgentDrawConfig> queryByConfigName(@Param("configName") String configName);

    /**
     * 查询所有拖拉拽配置
     * @return 拖拉拽配置列表
     */
    List<AiAgentDrawConfig> queryAll();

}