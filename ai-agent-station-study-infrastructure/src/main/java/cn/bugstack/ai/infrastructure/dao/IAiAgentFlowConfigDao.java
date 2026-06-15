package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgentFlowConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体-客户端关联表 DAO
 * @author bugstack虫洞栈
 * @description 智能体-客户端关联表数据访问对象
 */
@Mapper
public interface IAiAgentFlowConfigDao {

    /**
     * 插入智能体-客户端关联配置
     * @param aiAgentFlowConfig 智能体-客户端关联配置对象
     * @return 影响行数
     */
    int insert(AiAgentFlowConfig aiAgentFlowConfig);

    /**
     * 根据ID更新智能体-客户端关联配置
     * @param aiAgentFlowConfig 智能体-客户端关联配置对象
     * @return 影响行数
     */
    int updateById(AiAgentFlowConfig aiAgentFlowConfig);

    /**
     * 根据ID删除智能体-客户端关联配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(String id);

    /**
     * 根据智能体ID删除关联配置
     * @param agentId 智能体ID
     * @return 影响行数
     */
    int deleteByAgentId(String agentId);

    /**
     * 根据ID查询智能体-客户端关联配置
     * @param id 主键ID
     * @return 智能体-客户端关联配置对象
     */
    AiAgentFlowConfig queryById(String id);

    /**
     * 根据智能体ID查询关联配置列表
     * @param agentId 智能体ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByAgentId(String agentId);

    /**
     * 根据客户端ID查询关联配置列表
     * @param clientId 客户端ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByClientId(String clientId);

    /**
     * 根据智能体ID和客户端ID查询关联配置
     * @param agentId 智能体ID
     * @param clientId 客户端ID
     * @return 智能体-客户端关联配置对象
     */
    AiAgentFlowConfig queryByAgentIdAndClientId(@Param("agentId") String agentId, @Param("clientId") String clientId);

    /**
     * 查询所有智能体-客户端关联配置
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryAll();

}