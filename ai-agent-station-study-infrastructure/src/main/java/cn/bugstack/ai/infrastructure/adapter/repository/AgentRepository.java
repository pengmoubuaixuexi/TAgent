package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.*;
import cn.bugstack.ai.infrastructure.dao.*;
import cn.bugstack.ai.infrastructure.dao.po.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.stream.Collectors;

import static cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.*;

/**
 * AiAgent 仓储服务
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/28 18:09
 */
@Slf4j
@Repository
public class AgentRepository implements IAgentRepository {

    @Resource
    private IAiAgentDao aiAgentDao;

    @Resource
    private IAiAgentFlowConfigDao aiAgentFlowConfigDao;

    @Resource
    private IAiAgentTaskScheduleDao aiAgentTaskScheduleDao;

    @Resource
    private IAiClientAdvisorDao aiClientAdvisorDao;

    @Resource
    private IAiClientApiDao aiClientApiDao;

    @Resource
    private IAiClientConfigDao aiClientConfigDao;

    @Resource
    private IAiClientDao aiClientDao;

    @Resource
    private IAiClientModelDao aiClientModelDao;

    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;

    @Resource
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Resource
    private IAiMcpToolCatalogDao aiMcpToolCatalogDao;

    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Resource
    private IAiChatMemoryDao aiChatMemoryDao;

    @Resource
    private cn.bugstack.ai.infrastructure.adapter.repository.cache.MemoryCacheService memoryCache;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientApiVO> result = new ArrayList<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的modelId
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig config : configs) {
                if (AI_CLIENT_MODEL.getCode().equals(config.getTargetType()) && config.getStatus() == 1) {
                    String modelId = config.getTargetId();

                    // 2. 通过modelId查询模型配置，获取apiId
                    AiClientModel model = aiClientModelDao.queryByModelId(modelId);
                    if (model != null && model.getStatus() == 1) {
                        String apiId = model.getApiId();

                        // 3. 通过apiId查询API配置信息
                        AiClientApi apiConfig = aiClientApiDao.queryByApiId(apiId);
                        if (apiConfig != null && apiConfig.getStatus() == 1) {
                            // 4. 转换为VO对象
                            AiClientApiVO apiVO = AiClientApiVO.builder()
                                    .apiId(apiConfig.getApiId())
                                    .baseUrl(apiConfig.getBaseUrl())
                                    .apiKey(apiConfig.getApiKey())
                                    .completionsPath(apiConfig.getCompletionsPath())
                                    .embeddingsPath(apiConfig.getEmbeddingsPath())
                                    .build();

                            // 避免重复添加相同的API配置
                            if (result.stream().noneMatch(vo -> vo.getApiId().equals(apiVO.getApiId()))) {
                                result.add(apiVO);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientModelVO> result = new ArrayList<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的modelId
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig config : configs) {
                if (AI_CLIENT_MODEL.getCode().equals(config.getTargetType()) && config.getStatus() == 1) {
                    String modelId = config.getTargetId();

                    // 2. 通过modelId查询模型配置
                    AiClientModel model = aiClientModelDao.queryByModelId(modelId);
                    if (model != null && model.getStatus() == 1) {

                        // 3. 查询该模型关联的tool_mcp配置
                        List<AiClientConfig> toolMcpConfigs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT_MODEL.getCode(), modelId);
                        List<String> toolMcpIds = new ArrayList<>();

                        for (AiClientConfig toolMcpConfig : toolMcpConfigs) {
                            if (AI_CLIENT_TOOL_MCP.getCode().equals(toolMcpConfig.getTargetType()) && toolMcpConfig.getStatus() == 1) {
                                toolMcpIds.add(toolMcpConfig.getTargetId());
                            }
                        }

                        // 4. 转换为VO对象
                        AiClientModelVO modelVO = AiClientModelVO.builder()
                                .modelId(model.getModelId())
                                .apiId(model.getApiId())
                                .modelName(model.getModelName())
                                .modelType(model.getModelType())
                                .tier(model.getTier())
                                .capabilitiesJson(model.getCapabilitiesJson())
                                .toolMcpIds(toolMcpIds)
                                .build();

                        // 避免重复添加相同的模型配置
                        if (result.stream().noneMatch(vo -> vo.getModelId().equals(modelVO.getModelId()))) {
                            result.add(modelVO);
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientToolMcpVO> result = new ArrayList<>();
        Set<String> processedMcpIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的model配置
            List<AiClientConfig> clientConfigs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig clientConfig : clientConfigs) {
                if (AI_CLIENT_MODEL.getCode().equals(clientConfig.getTargetType()) && clientConfig.getStatus() == 1) {
                    String modelId = clientConfig.getTargetId();

                    // 2. 通过modelId查询关联的tool_mcp配置
                    List<AiClientConfig> modelConfigs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT_MODEL.getCode(), modelId);

                    for (AiClientConfig modelConfig : modelConfigs) {
                        if (AI_CLIENT_TOOL_MCP.getCode().equals(modelConfig.getTargetType()) && modelConfig.getStatus() == 1) {
                            String mcpId = modelConfig.getTargetId();

                            // 避免重复处理相同的mcpId
                            if (processedMcpIds.contains(mcpId)) {
                                continue;
                            }
                            processedMcpIds.add(mcpId);

                            // 3. 通过mcpId查询ai_client_tool_mcp表获取MCP工具配置
                            AiClientToolMcp toolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
                            if (toolMcp != null && toolMcp.getStatus() == 1) {
                                // 4. 转换为VO对象
                                AiClientToolMcpVO mcpVO = AiClientToolMcpVO.builder()
                                        .mcpId(toolMcp.getMcpId())
                                        .mcpName(toolMcp.getMcpName())
                                        .transportType(toolMcp.getTransportType())
                                        .transportConfig(toolMcp.getTransportConfig())
                                        .requestTimeout(toolMcp.getRequestTimeout())
                                        .build();

                                // 复用 parseMcpTransportConfig 解析 sse/stdio；保持原语义：无论解析成功与否都加入结果
                                parseMcpTransportConfig(toolMcp, mcpVO);
                                result.add(mcpVO);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public AiClientToolMcpVO queryAiClientToolMcpVOByMcpId(String mcpId) {
        if (mcpId == null || mcpId.isBlank()) {
            return null;
        }
        AiClientToolMcp toolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
        if (toolMcp == null || toolMcp.getStatus() == null || toolMcp.getStatus() != 1) {
            return null;
        }
        return toMcpVO(toolMcp);
    }

    @Override
    public List<AiClientToolMcpVO> queryEnabledAiClientToolMcpVOList() {
        List<AiClientToolMcp> toolMcps = aiClientToolMcpDao.queryEnabledMcps();
        if (toolMcps == null || toolMcps.isEmpty()) {
            return List.of();
        }
        List<AiClientToolMcpVO> result = new ArrayList<>(toolMcps.size());
        for (AiClientToolMcp toolMcp : toolMcps) {
            if (toolMcp == null) continue;
            AiClientToolMcpVO mcpVO = toMcpVO(toolMcp);
            if (mcpVO != null) {
                result.add(mcpVO);
            }
        }
        return result;
    }

    @Override
    public List<AiMcpToolCatalogVO> queryEnabledMcpToolCatalog() {
        return toCatalogVOList(aiMcpToolCatalogDao.queryEnabled());
    }

    @Override
    public List<AiMcpToolCatalogVO> queryMcpToolCatalogByMcpId(String mcpId) {
        if (mcpId == null || mcpId.isBlank()) {
            return List.of();
        }
        return toCatalogVOList(aiMcpToolCatalogDao.queryByMcpId(mcpId));
    }

    private List<AiMcpToolCatalogVO> toCatalogVOList(List<AiMcpToolCatalog> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AiMcpToolCatalogVO> result = new ArrayList<>(rows.size());
        for (AiMcpToolCatalog row : rows) {
            result.add(AiMcpToolCatalogVO.builder()
                    .id(row.getId())
                    .mcpId(row.getMcpId())
                    .mcpName(row.getMcpName())
                    .toolName(row.getToolName())
                    .toolDescription(row.getToolDescription())
                    .toolDescriptionZh(row.getToolDescriptionZh())
                    .toolIntentZh(row.getToolIntentZh())
                    .inputSchemaJson(row.getInputSchemaJson())
                    .enabled(row.getEnabled())
                    .lastSeenAt(row.getLastSeenAt())
                    .createTime(row.getCreateTime())
                    .updateTime(row.getUpdateTime())
                    .build());
        }
        return result;
    }

    @Override
    public void upsertMcpToolCatalog(List<AiMcpToolCatalogVO> catalogList) {
        if (catalogList == null || catalogList.isEmpty()) {
            return;
        }
        List<AiMcpToolCatalog> rows = new ArrayList<>(catalogList.size());
        for (AiMcpToolCatalogVO vo : catalogList) {
            if (vo == null || vo.getMcpId() == null || vo.getToolName() == null) continue;
            rows.add(AiMcpToolCatalog.builder()
                    .mcpId(vo.getMcpId())
                    .mcpName(vo.getMcpName())
                    .toolName(vo.getToolName())
                    .toolDescription(vo.getToolDescription())
                    .toolDescriptionZh(vo.getToolDescriptionZh())
                    .toolIntentZh(vo.getToolIntentZh())
                    .inputSchemaJson(vo.getInputSchemaJson())
                    .enabled(vo.getEnabled() == null ? 1 : vo.getEnabled())
                    .lastSeenAt(vo.getLastSeenAt() == null ? java.time.LocalDateTime.now() : vo.getLastSeenAt())
                    .build());
        }
        if (!rows.isEmpty()) {
            aiMcpToolCatalogDao.upsertBatch(rows);
        }
    }

    @Override
    public void deleteMcpToolCatalog(String mcpId, List<String> toolNames) {
        if (mcpId == null || mcpId.isBlank() || toolNames == null || toolNames.isEmpty()) {
            return;
        }
        aiMcpToolCatalogDao.deleteByMcpIdAndToolNames(mcpId, toolNames);
    }

    @Override
    public List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientSystemPromptVO> result = new ArrayList<>();
        Set<String> processedPromptIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 通过clientId查询关联的prompt配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

            for (AiClientConfig config : configs) {
                if ("prompt".equals(config.getTargetType()) && config.getStatus() == 1) {
                    String promptId = config.getTargetId();

                    // 避免重复处理相同的promptId
                    if (processedPromptIds.contains(promptId)) {
                        continue;
                    }
                    processedPromptIds.add(promptId);

                    // 2. 通过promptId查询ai_client_system_prompt表获取系统提示词配置
                    AiClientSystemPrompt systemPrompt = aiClientSystemPromptDao.queryByPromptId(promptId);
                    if (systemPrompt != null && systemPrompt.getStatus() == 1) {
                        // 3. 转换为VO对象
                        AiClientSystemPromptVO promptVO = AiClientSystemPromptVO.builder()
                                .promptId(systemPrompt.getPromptId())
                                .promptName(systemPrompt.getPromptName())
                                .promptContent(systemPrompt.getPromptContent())
                                .description(systemPrompt.getDescription())
                                .build();

                        result.add(promptVO);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList) {
        List<AiClientSystemPromptVO> aiClientSystemPrompts = AiClientSystemPromptVOByClientIds(clientIdList);

        if (null == aiClientSystemPrompts || aiClientSystemPrompts.isEmpty()) {
            return Collections.emptyMap();
        }

        // 将PO对象转换为VO对象，并构建Map结构
        return aiClientSystemPrompts.stream()
                .map(prompt -> AiClientSystemPromptVO.builder()
                        .promptId(prompt.getPromptId())
                        .promptContent(prompt.getPromptContent())
                        .build())
                .collect(Collectors.toMap(
                        AiClientSystemPromptVO::getPromptId,  // key: id
                        prompt -> prompt,               // value: AiClientSystemPromptVO对象
                        (existing, replacement) -> existing  // 如果有重复key，保留第一个
                ));
    }

    @Override
    public List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientAdvisorVO> result = new ArrayList<>();
        Set<String> processedAdvisorIds = new HashSet<>();

        for (String clientId : clientIdList) {
            // 1. 查询客户端相关的advisor配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId("client", clientId);

            for (AiClientConfig config : configs) {
                if (config.getStatus() != 1 || !"advisor".equals(config.getTargetType())) {
                    continue;
                }

                String advisorId = config.getTargetId();
                if (processedAdvisorIds.contains(advisorId)) {
                    continue;
                }
                processedAdvisorIds.add(advisorId);

                // 2. 查询advisor详细信息
                AiClientAdvisor aiClientAdvisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
                if (aiClientAdvisor == null || aiClientAdvisor.getStatus() != 1) {
                    continue;
                }

                // 3. 解析extParam中的配置
                AiClientAdvisorVO.ChatMemory chatMemory = null;
                AiClientAdvisorVO.RagAnswer ragAnswer = null;

                String extParam = aiClientAdvisor.getExtParam();
                if (extParam != null && !extParam.trim().isEmpty()) {
                    try {
                        if ("ChatMemory".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析chatMemory配置
                            chatMemory = JSON.parseObject(extParam, AiClientAdvisorVO.ChatMemory.class);
                        } else if ("RagAnswer".equals(aiClientAdvisor.getAdvisorType())) {
                            // 解析ragAnswer配置
                            ragAnswer = JSON.parseObject(extParam, AiClientAdvisorVO.RagAnswer.class);
                        }
                    } catch (Exception e) {
                        // 解析失败时忽略，使用默认值null
                    }
                }

                // 4. 构建AiClientAdvisorVO对象
                AiClientAdvisorVO advisorVO = AiClientAdvisorVO.builder()
                        .advisorId(aiClientAdvisor.getAdvisorId())
                        .advisorName(aiClientAdvisor.getAdvisorName())
                        .advisorType(aiClientAdvisor.getAdvisorType())
                        .orderNum(aiClientAdvisor.getOrderNum())
                        .chatMemory(chatMemory)
                        .ragAnswer(ragAnswer)
                        .build();

                result.add(advisorVO);
            }
        }

        return result;
    }

    @Override
    public List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList) {
        if (clientIdList == null || clientIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientVO> result = new ArrayList<>();
        Set<String> processedClientIds = new HashSet<>();

        for (String clientId : clientIdList) {
            if (processedClientIds.contains(clientId)) {
                continue;
            }
            processedClientIds.add(clientId);

            // 1. 查询客户端基本信息
            AiClient aiClient = aiClientDao.queryByClientId(clientId);
            if (aiClient == null || aiClient.getStatus() != 1) {
                continue;
            }

            // 2. 查询客户端相关配置
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId("client", clientId);

            String modelId = null;
            List<String> promptIdList = new ArrayList<>();
            List<String> mcpIdList = new ArrayList<>();
            List<String> advisorIdList = new ArrayList<>();

            for (AiClientConfig config : configs) {
                if (config.getStatus() != 1) {
                    continue;
                }

                switch (config.getTargetType()) {
                    case "model":
                        modelId = config.getTargetId();
                        break;
                    case "prompt":
                        promptIdList.add(config.getTargetId());
                        break;
                    case "tool_mcp":
                        mcpIdList.add(config.getTargetId());
                        break;
                    case "advisor":
                        advisorIdList.add(config.getTargetId());
                        break;
                }
            }

            // 3. 构建AiClientVO对象
            AiClientVO aiClientVO = AiClientVO.builder()
                    .clientId(aiClient.getClientId())
                    .clientName(aiClient.getClientName())
                    .description(aiClient.getDescription())
                    .modelId(modelId)
                    .promptIdList(promptIdList)
                    .mcpIdList(mcpIdList)
                    .advisorIdList(advisorIdList)
                    .build();

            result.add(aiClientVO);
        }

        return result;
    }

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) {
        if (modelIdList == null || modelIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientApiVO> result = new ArrayList<>();

        for (String modelId : modelIdList) {
            // 1. 通过modelId查询模型配置，获取apiId
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                String apiId = model.getApiId();

                // 2. 通过apiId查询API配置信息
                AiClientApi apiConfig = aiClientApiDao.queryByApiId(apiId);
                if (apiConfig != null && apiConfig.getStatus() == 1) {
                    // 3. 转换为VO对象
                    AiClientApiVO apiVO = AiClientApiVO.builder()
                            .apiId(apiConfig.getApiId())
                            .baseUrl(apiConfig.getBaseUrl())
                            .apiKey(apiConfig.getApiKey())
                            .completionsPath(apiConfig.getCompletionsPath())
                            .embeddingsPath(apiConfig.getEmbeddingsPath())
                            .build();

                    // 避免重复添加相同的API配置
                    if (result.stream().noneMatch(vo -> vo.getApiId().equals(apiVO.getApiId()))) {
                        result.add(apiVO);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList) {
        if (modelIdList == null || modelIdList.isEmpty()) {
            return List.of();
        }

        List<AiClientModelVO> result = new ArrayList<>();

        for (String modelId : modelIdList) {
            // 通过modelId查询模型配置
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                // 转换为VO对象
                AiClientModelVO modelVO = AiClientModelVO.builder()
                        .modelId(model.getModelId())
                        .apiId(model.getApiId())
                        .modelName(model.getModelName())
                        .modelType(model.getModelType())
                        .tier(model.getTier())
                        .capabilitiesJson(model.getCapabilitiesJson())
                        .build();

                // 避免重复添加相同的模型配置
                if (result.stream().noneMatch(vo -> vo.getModelId().equals(modelVO.getModelId()))) {
                    result.add(modelVO);
                }
            }
        }

        return result;
    }

    @Override
    public List<AiClientModelVO> queryEnabledAiClientModelVOList() {
        List<AiClientModelVO> result = new ArrayList<>();
        List<AiClientModel> models = aiClientModelDao.queryEnabledModels();
        if (models == null) {
            return result;
        }
        for (AiClientModel model : models) {
            result.add(AiClientModelVO.builder()
                    .modelId(model.getModelId())
                    .apiId(model.getApiId())
                    .modelName(model.getModelName())
                    .modelType(model.getModelType())
                    .tier(model.getTier())
                    .capabilitiesJson(model.getCapabilitiesJson())
                    .build());
        }
        return result;
    }

    @Override
    public Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId) {
        if (aiAgentId == null || aiAgentId.trim().isEmpty()) {
            return Map.of();
        }

        try {
            // 根据智能体ID查询流程配置列表
            List<AiAgentFlowConfig> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(aiAgentId);

            if (flowConfigs == null || flowConfigs.isEmpty()) {
                return Map.of();
            }

            // 转换为Map结构，key为clientId，value为AiAgentClientFlowConfigVO
            Map<String, AiAgentClientFlowConfigVO> result = new HashMap<>();

            for (AiAgentFlowConfig flowConfig : flowConfigs) {
                AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                        .clientId(flowConfig.getClientId())
                        .clientName(flowConfig.getClientName())
                        .clientType(flowConfig.getClientType())
                        .sequence(flowConfig.getSequence())
                        .stepPrompt(flowConfig.getStepPrompt())
                        .build();

                result.put(flowConfig.getClientType(), configVO);
            }

            return result;
        } catch (NumberFormatException e) {
            log.error("Invalid aiAgentId format: {}", aiAgentId, e);
            return Map.of();
        } catch (Exception e) {
            log.error("Query ai agent client flow config failed, aiAgentId: {}", aiAgentId, e);
            return Map.of();
        }
    }

    @Override
    public AiAgentVO queryAiAgentByAgentId(String aiAgentId) {
        AiAgent aiAgent = aiAgentDao.queryByAgentId(aiAgentId);

        return AiAgentVO.builder()
                .agentId(aiAgent.getAgentId())
                .agentName(aiAgent.getAgentName())
                .description(aiAgent.getDescription())
                .channel(aiAgent.getChannel())
                .strategy(aiAgent.getStrategy())
                .status(aiAgent.getStatus())
                .build();
    }

    @Override
    public List<AiAgentClientFlowConfigVO> queryAiAgentClientsByAgentId(String aiAgentId) {
        List<AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOS = new ArrayList<>();

        List<AiAgentFlowConfig> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(aiAgentId);
        for (AiAgentFlowConfig flowConfig : flowConfigs) {
            AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                    .clientId(flowConfig.getClientId())
                    .clientName(flowConfig.getClientName())
                    .clientType(flowConfig.getClientType())
                    .sequence(flowConfig.getSequence())
                    .stepPrompt(flowConfig.getStepPrompt())
                    .build();

            aiAgentClientFlowConfigVOS.add(configVO);
        }

        return aiAgentClientFlowConfigVOS;
    }

    @Override
    public List<AiAgentTaskScheduleVO> queryAllValidTaskSchedule() {
        List<AiAgentTaskSchedule> aiAgentTaskSchedules = aiAgentTaskScheduleDao.queryAllValidTaskSchedule();

        List<AiAgentTaskScheduleVO> result = new ArrayList<>();
        for (AiAgentTaskSchedule taskSchedule : aiAgentTaskSchedules) {
            AiAgentTaskScheduleVO taskScheduleVO = AiAgentTaskScheduleVO.builder()
                    .id(taskSchedule.getId())
                    .agentId(taskSchedule.getAgentId())
                    .description(taskSchedule.getDescription())
                    .cronExpression(taskSchedule.getCronExpression())
                    .taskParam(taskSchedule.getTaskParam())
                    .build();
            result.add(taskScheduleVO);
        }

        return result;
    }

    @Override
    public List<Long> queryAllInvalidTaskScheduleIds() {
        return aiAgentTaskScheduleDao.queryAllInvalidTaskScheduleIds();
    }

    @Override
    public void createTagOrder(AiRagOrderVO aiRagOrderVO) {
        AiClientRagOrder aiRagOrder = new AiClientRagOrder();
        aiRagOrder.setRagId(aiRagOrderVO.getRagId());
        aiRagOrder.setRagName(aiRagOrderVO.getRagName());
        aiRagOrder.setKnowledgeTag(aiRagOrderVO.getKnowledgeTag());
        aiRagOrder.setFileHash(aiRagOrderVO.getFileHash());
        aiRagOrder.setFileSize(aiRagOrderVO.getFileSize());
        aiRagOrder.setUserId(aiRagOrderVO.getUserId());
        aiRagOrder.setStatus(1);
        aiClientRagOrderDao.insert(aiRagOrder);
    }

    /**
     * 按 SHA-256 + knowledge_tag + user_id 查重，存在则返回 true。
     * 用于 RagService.storeRagFile 入库前判断是否重复文件。
     */
    @Override
    public boolean existsRagFileByHashTagAndUser(String fileHash, String knowledgeTag, String userId) {
        if (fileHash == null || fileHash.isBlank()) return false;
        String normalizedUserId = (userId == null || userId.isBlank()) ? null : userId;
        return aiClientRagOrderDao.countByFileHashTagAndUser(fileHash, knowledgeTag, normalizedUserId) > 0;
    }

    @Override
    public List<AiAgentVO> queryAvailableAgents() {
        List<AiAgent> aiAgents = aiAgentDao.queryEnabledAgents();
        List<AiAgentVO> aiAgentVOS = new ArrayList<>();
        for (AiAgent aiAgent : aiAgents) {
            aiAgentVOS.add(AiAgentVO.builder()
                    .agentId(aiAgent.getAgentId())
                    .agentName(aiAgent.getAgentName())
                    .description(aiAgent.getDescription())
                    .channel(aiAgent.getChannel())
                    .strategy(aiAgent.getStrategy())
                    .status(aiAgent.getStatus())
                    .build());
        }
        return aiAgentVOS;
    }

    @Override
    public List<AiAgentVO> queryAiAgentsByStrategy(String strategy) {
        List<AiAgent> aiAgents = aiAgentDao.queryByStrategy(strategy, 1);
        List<AiAgentVO> result = new ArrayList<>();
        for (AiAgent a : aiAgents) {
            result.add(AiAgentVO.builder()
                    .agentId(a.getAgentId())
                    .agentName(a.getAgentName())
                    .description(a.getDescription())
                    .channel(a.getChannel())
                    .strategy(a.getStrategy())
                    .status(a.getStatus())
                    .build());
        }
        return result;
    }

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByApiIds(List<String> apiIdList) {
        List<AiClientApiVO> aiClientApiVOS = new ArrayList<>();
        for (String apiId : apiIdList) {
            AiClientApi aiClientApi = aiClientApiDao.queryByApiId(apiId);
            aiClientApiVOS.add(AiClientApiVO.builder()
                    .apiId(aiClientApi.getApiId())
                    .baseUrl(aiClientApi.getBaseUrl())
                    .apiKey(aiClientApi.getApiKey())
                    .completionsPath(aiClientApi.getCompletionsPath())
                    .embeddingsPath(aiClientApi.getEmbeddingsPath())
                    .build());
        }
        return aiClientApiVOS;
    }

    @Override
    public Long getMaxConfigUpdateTime() {
        try {
            String sql = """
                    SELECT MAX(t) FROM (
                        SELECT MAX(update_time) AS t FROM ai_agent
                        UNION ALL SELECT MAX(update_time) FROM ai_client
                        UNION ALL SELECT MAX(update_time) FROM ai_client_config
                        UNION ALL SELECT MAX(update_time) FROM ai_client_model
                        UNION ALL SELECT MAX(update_time) FROM ai_client_advisor
                        UNION ALL SELECT MAX(update_time) FROM ai_client_system_prompt
                    ) AS combined
                    """;
            java.sql.Timestamp ts = jdbcTemplate.queryForObject(sql, java.sql.Timestamp.class);
            return ts != null ? ts.getTime() : null;
        } catch (Exception e) {
            log.debug("getMaxConfigUpdateTime failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int countChatMemoryByConversationId(String conversationId) {
        // 1) 旧 msg_count 缓存：仍保留作为 Step4 episodic 节流的最快路径
        String redisKey = "episodic:msg_count:" + conversationId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                stringRedisTemplate.expire(redisKey, java.time.Duration.ofHours(1));
                return Integer.parseInt(cached);
            }
        } catch (Exception e) {
            log.debug("Redis get failed for {}, fallback to list cache: {}", redisKey, e.getMessage());
        }
        // 2) chat_memory 列表缓存：能复用就别再查 DB
        List<AiChatMemory> cachedList = memoryCache.getChatList(conversationId);
        int count;
        if (cachedList != null) {
            count = cachedList.size();
        } else {
            List<AiChatMemory> rows = aiChatMemoryDao.findByConversationId(conversationId);
            count = rows == null ? 0 : rows.size();
            if (rows != null && !rows.isEmpty()) {
                memoryCache.putChatList(conversationId, rows);
            }
        }
        try {
            stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(count), java.time.Duration.ofHours(1));
        } catch (Exception e) {
            log.debug("Redis set failed for {}: {}", redisKey, e.getMessage());
        }
        return count;
    }

    @Override
    public void updateChatMemoryCount(String conversationId, int count) {
        String redisKey = "episodic:msg_count:" + conversationId;
        try {
            stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(count), java.time.Duration.ofHours(1));
        } catch (Exception e) {
            log.debug("Redis set failed for {}: {}", redisKey, e.getMessage());
        }
    }

    @Override
    public List<String> findChatMemoryTextsByConversationId(String conversationId) {
        List<AiChatMemory> rows = memoryCache.getChatList(conversationId);
        if (rows == null) {
            rows = aiChatMemoryDao.findByConversationId(conversationId);
            if (rows != null && !rows.isEmpty()) {
                memoryCache.putChatList(conversationId, rows);
            }
        }
        if (rows == null || rows.isEmpty()) return List.of();
        List<String> texts = new ArrayList<>(rows.size());
        for (AiChatMemory r : rows) {
            if (r.getContent() != null && !r.getContent().isBlank()) {
                String role = r.getMessageType() != null ? r.getMessageType() : "?";
                texts.add("[" + role + "] " + r.getContent());
            }
        }
        return texts;
    }

    private AiClientToolMcpVO toMcpVO(AiClientToolMcp toolMcp) {
        AiClientToolMcpVO mcpVO = AiClientToolMcpVO.builder()
                .mcpId(toolMcp.getMcpId())
                .mcpName(toolMcp.getMcpName())
                .transportType(toolMcp.getTransportType())
                .transportConfig(toolMcp.getTransportConfig())
                .requestTimeout(toolMcp.getRequestTimeout())
                .build();
        return parseMcpTransportConfig(toolMcp, mcpVO) ? mcpVO : null;
    }

    private boolean parseMcpTransportConfig(AiClientToolMcp toolMcp, AiClientToolMcpVO mcpVO) {
        String transportConfig = toolMcp.getTransportConfig();
        String transportType = toolMcp.getTransportType() == null ? "" : toolMcp.getTransportType().trim().toLowerCase(Locale.ROOT);
        try {
            if (transportConfig == null || transportConfig.isBlank()) {
                log.warn("skip mcp config: empty transport_config mcpId={} name={}", toolMcp.getMcpId(), toolMcp.getMcpName());
                return false;
            }
            ObjectMapper objectMapper = new ObjectMapper();
            if ("sse".equals(transportType)) {
                AiClientToolMcpVO.TransportConfigSse transportConfigSse = objectMapper.readValue(transportConfig, AiClientToolMcpVO.TransportConfigSse.class);
                mcpVO.setTransportConfigSse(transportConfigSse);
                return true;
            } else if ("stdio".equals(transportType)) {
                Map<String, AiClientToolMcpVO.TransportConfigStdio.Stdio> stdio = objectMapper.readValue(transportConfig,
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = new AiClientToolMcpVO.TransportConfigStdio();
                transportConfigStdio.setStdio(stdio);
                mcpVO.setTransportConfigStdio(transportConfigStdio);
                return true;
            } else if ("streamable-http".equals(transportType) || "streamablehttp".equals(transportType)) {
                AiClientToolMcpVO.TransportConfigStreamableHttp streamableHttp = objectMapper.readValue(
                        transportConfig, AiClientToolMcpVO.TransportConfigStreamableHttp.class);
                mcpVO.setTransportConfigStreamableHttp(streamableHttp);
                return true;
            }
            log.warn("skip mcp config: unsupported transport_type mcpId={} name={} type={}",
                    toolMcp.getMcpId(), toolMcp.getMcpName(), toolMcp.getTransportType());
            return false;
        } catch (Exception e) {
            log.warn("skip mcp config: parse transport config failed mcpId={} name={} type={} error={}",
                    toolMcp.getMcpId(), toolMcp.getMcpName(), toolMcp.getTransportType(), e.getMessage());
            return false;
        }
    }

}
