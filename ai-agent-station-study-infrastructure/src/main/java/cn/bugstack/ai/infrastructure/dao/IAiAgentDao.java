package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiAgent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI智能体配置表 DAO
 * @author bugstack虫洞栈
 * @description AI智能体配置表数据访问对象
 */
@Mapper
public interface IAiAgentDao {

    /**
     * 插入AI智能体配置
     * @param aiAgent AI智能体配置对象
     * @return 影响行数
     */
    int insert(AiAgent aiAgent);

    /**
     * 根据ID更新AI智能体配置
     * @param aiAgent AI智能体配置对象
     * @return 影响行数
     */
    int updateById(AiAgent aiAgent);

    /**
     * 根据智能体ID更新AI智能体配置
     * @param aiAgent AI智能体配置对象
     * @return 影响行数
     */
    int updateByAgentId(AiAgent aiAgent);

    /**
     * 根据ID删除AI智能体配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据智能体ID删除AI智能体配置
     * @param agentId 智能体ID
     * @return 影响行数
     */
    int deleteByAgentId(@Param("agentId") String agentId);

    /**
     * 根据ID查询AI智能体配置
     * @param id 主键ID
     * @return AI智能体配置对象
     */
    AiAgent queryById(Long id);

    /**
     * 根据智能体ID查询AI智能体配置
     * @param agentId 智能体ID
     * @return AI智能体配置对象
     */
    AiAgent queryByAgentId(@Param("agentId") String agentId);

    /**
     * 查询所有启用的AI智能体配置
     * @return AI智能体配置列表
     */
    List<AiAgent> queryEnabledAgents();

    /**
     * 根据渠道类型查询AI智能体配置
     * @param channel 渠道类型
     * @return AI智能体配置列表
     */
    List<AiAgent> queryByChannel(@Param("channel") String channel);

    /**
     * 查询所有AI智能体配置
     * @return AI智能体配置列表
     */
    List<AiAgent> queryAll();

    /**
     * 根据执行策略 + 启用状态查询智能体列表（用于意图路由后的 agent 池选择）
     * @param strategy 执行策略 bean 名（如 fixedAgentExecuteStrategy）
     * @param status   状态（1=启用）
     * @return 匹配的智能体列表
     */
    List<AiAgent> queryByStrategy(@Param("strategy") String strategy, @Param("status") Integer status);

}