package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.*;

import java.util.List;
import java.util.Map;

/**
 * AiAgent 仓储接口
 *
 * @author TAgent
 * 2025/6/27 16:48
 */
public interface IAgentRepository {

    List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList);

    List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList);

    List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList);

    List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList);

    Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList);

    List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList);

    List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList);

    List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList);

    List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList);

    Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId);

    AiAgentVO queryAiAgentByAgentId(String aiAgentId);

    List<AiAgentClientFlowConfigVO> queryAiAgentClientsByAgentId(String aiAgentId);

    List<AiAgentTaskScheduleVO> queryAllValidTaskSchedule();

    List<Long> queryAllInvalidTaskScheduleIds();

    void createTagOrder(AiRagOrderVO aiRagOrderVO);

    /**
     * 按文件 SHA-256 + knowledge_tag 查重，存在则跳过重复入库（同文件可导入不同知识库）
     * @param fileHash 文件的 SHA-256 哈希
     * @param knowledgeTag 知识库标签
     * @return true=已存在
     */
    boolean existsRagFileByHashAndTag(String fileHash, String knowledgeTag);

    /**
     * 查询可用的智能体列表
     * @return 可用的智能体列表
     */
    List<AiAgentVO> queryAvailableAgents();

    /**
     * 按执行策略查询启用的智能体列表（意图路由后 agent 池选择）
     * @param strategy 执行策略 bean 名
     * @return 该策略下的启用 agent 列表
     */
    List<AiAgentVO> queryAiAgentsByStrategy(String strategy);

    List<AiClientApiVO> queryAiClientApiVOListByApiIds(List<String> apiIdList);

    /** P2.8 17.3 配置热加载：查 ai_client 表最大 update_time epoch millis */
    Long getMaxConfigUpdateTime();

    /** 查询会话的真实消息总数（Redis 缓存，绕过滑动窗口截断） */
    int countChatMemoryByConversationId(String conversationId);

    /** 更新 Redis 中缓存的消息计数（add 时调用，避免每次查 DB） */
    void updateChatMemoryCount(String conversationId, int count);

    /** 查询会话的全部消息内容（绕过滑动窗口截断，用于 episodic 摘要） */
    List<String> findChatMemoryTextsByConversationId(String conversationId);

}
