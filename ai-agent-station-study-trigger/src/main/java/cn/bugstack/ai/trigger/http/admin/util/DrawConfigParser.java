package cn.bugstack.ai.trigger.http.admin.util;

import cn.bugstack.ai.infrastructure.dao.po.AiClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 拖拽配置解析工具类
 * 用于解析前端传来的JSON配置数据，生成ai_client_config表的关系映射
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/1/20 10:00
 */
@Slf4j
public class DrawConfigParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析拖拽配置JSON数据，生成AiClientConfig关系列表
     *
     * @param configData JSON配置数据
     * @return AiClientConfig关系列表
     */
    public static List<AiClientConfig> parseConfigData(String configData) {
        List<AiClientConfig> configList = new ArrayList<>();
        
        try {
            JsonNode rootNode = objectMapper.readTree(configData);
            JsonNode nodesArray = rootNode.get("nodes");
            JsonNode edgesArray = rootNode.get("edges");
            
            if (nodesArray == null || edgesArray == null) {
                log.warn("配置数据中缺少nodes或edges节点");
                return configList;
            }
            
            log.info("开始解析配置数据，包含{}个节点，{}条边", nodesArray.size(), edgesArray.size());
            
            // 构建节点映射表，key为nodeId，value为节点信息
            Map<String, NodeInfo> nodeMap = buildNodeMap(nodesArray);
            
            // 解析边关系，生成配置关系
            parseEdges(edgesArray, nodeMap, configList);
            
            // 验证和统计解析结果
            validateAndLogResults(nodeMap, configList, edgesArray.size());
            
            log.info("解析配置数据完成，生成{}条关系", configList.size());
            
        } catch (Exception e) {
            log.error("解析配置数据失败", e);
        }
        
        return configList;
    }

    /**
     * 构建节点映射表
     */
    private static Map<String, NodeInfo> buildNodeMap(JsonNode nodesArray) {
        Map<String, NodeInfo> nodeMap = new HashMap<>();
        
        log.info("开始构建节点映射表，总共{}个节点", nodesArray.size());
        
        for (JsonNode nodeJson : nodesArray) {
            String nodeId = nodeJson.get("id").asText();
            String nodeType = nodeJson.get("type").asText();
            
            NodeInfo nodeInfo = new NodeInfo();
            nodeInfo.setNodeId(nodeId);
            nodeInfo.setNodeType(nodeType);
            
            // 解析节点数据，提取引用ID
            JsonNode dataNode = nodeJson.get("data");
            if (dataNode != null) {
                nodeInfo.setTitle(dataNode.has("title") ? dataNode.get("title").asText() : "");
                
                // 解析inputsValues，提取具体的配置值
                JsonNode inputsValues = dataNode.get("inputsValues");
                if (inputsValues != null) {
                    extractRefId(inputsValues, nodeInfo);
                }
            }
            
            log.debug("构建节点信息: {}", nodeInfo);
            nodeMap.put(nodeId, nodeInfo);
        }
        
        log.info("节点映射表构建完成，共{}个节点", nodeMap.size());
        return nodeMap;
    }

    /**
     * 从inputsValues中提取引用ID
     */
    private static void extractRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        // 根据不同节点类型提取不同的引用ID
        switch (nodeInfo.getNodeType()) {
            case "client":
                extractClientRefId(inputsValues, nodeInfo);
                break;
            case "agent":
                extractAgentRefId(inputsValues, nodeInfo);
                break;
            case "tool_mcp":
                extractToolMcpRefId(inputsValues, nodeInfo);
                break;
            case "model":
                extractModelRefId(inputsValues, nodeInfo);
                break;
            case "prompt":
                extractPromptRefId(inputsValues, nodeInfo);
                break;
            case "advisor":
                extractAdvisorRefId(inputsValues, nodeInfo);
                break;
            default:
                // 其他类型节点暂不处理
                log.debug("未处理的节点类型: {}", nodeInfo.getNodeType());
                break;
        }
    }

    /**
     * 提取客户端引用ID
     */
    private static void extractClientRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        // 优先使用clientId字段（直接字符串值）
        JsonNode clientId = inputsValues.get("clientId");
        if (clientId != null && clientId.isTextual()) {
            nodeInfo.setRefId(clientId.asText());
            return;
        }
        
        // 如果没有clientId，则使用clientName数组
        JsonNode clientName = inputsValues.get("clientName");
        if (clientName != null && clientName.isArray() && !clientName.isEmpty()) {
            JsonNode firstItem = clientName.get(0);
            if (firstItem.has("value")) {
                nodeInfo.setRefId(firstItem.get("value").asText());
            }
        }
    }

    /**
     * 提取智能体引用ID
     */
    private static void extractAgentRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        JsonNode agentName = inputsValues.get("agentName");
        if (agentName != null) {
            if (agentName.isArray() && agentName.size() > 0) {
                // 数组格式：[{"key": "xxx", "value": "yyy"}]
                JsonNode firstItem = agentName.get(0);
                if (firstItem.has("value")) {
                    nodeInfo.setRefId(firstItem.get("value").asText());
                }
            } else if (agentName.isTextual()) {
                // 字符串格式：直接使用字符串值作为引用ID
                nodeInfo.setRefId(agentName.asText());
            }
        }
    }

    /**
     * 提取工具MCP引用ID
     */
    private static void extractToolMcpRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        JsonNode toolMcpName = inputsValues.get("toolMcpName");
        if (toolMcpName != null && toolMcpName.isArray() && toolMcpName.size() > 0) {
            JsonNode firstItem = toolMcpName.get(0);
            if (firstItem.has("value")) {
                nodeInfo.setRefId(firstItem.get("value").asText());
            }
        }
    }

    /**
     * 提取模型引用ID
     */
    private static void extractModelRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        JsonNode modelName = inputsValues.get("modelName");
        if (modelName != null && modelName.isArray() && modelName.size() > 0) {
            JsonNode firstItem = modelName.get(0);
            if (firstItem.has("value")) {
                nodeInfo.setRefId(firstItem.get("value").asText());
            }
        }
    }

    /**
     * 提取提示词引用ID
     */
    private static void extractPromptRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        JsonNode promptName = inputsValues.get("promptName");
        if (promptName != null) {
            if (promptName.isArray() && promptName.size() > 0) {
                // 数组格式：[{"key": "xxx", "value": "yyy"}]
                JsonNode firstItem = promptName.get(0);
                if (firstItem.has("value")) {
                    nodeInfo.setRefId(firstItem.get("value").asText());
                }
            } else if (promptName.isTextual()) {
                // 字符串格式：直接使用字符串值作为引用ID
                nodeInfo.setRefId(promptName.asText());
            }
        }
    }

    /**
     * 提取顾问引用ID
     */
    private static void extractAdvisorRefId(JsonNode inputsValues, NodeInfo nodeInfo) {
        JsonNode advisorName = inputsValues.get("advisorName");
        if (advisorName != null && advisorName.isArray() && advisorName.size() > 0) {
            JsonNode firstItem = advisorName.get(0);
            if (firstItem.has("value")) {
                nodeInfo.setRefId(firstItem.get("value").asText());
            }
        }
    }

    /**
     * 解析边关系，生成配置关系
     */
    private static void parseEdges(JsonNode edgesArray, Map<String, NodeInfo> nodeMap, List<AiClientConfig> configList) {
        log.info("开始解析边关系，总共{}条边", edgesArray.size());
        
        int processedEdges = 0;
        int skippedEdges = 0;
        int validConfigs = 0;
        
        for (JsonNode edgeJson : edgesArray) {
            processedEdges++;
            String sourceNodeId = edgeJson.get("sourceNodeID").asText();
            String targetNodeId = edgeJson.get("targetNodeID").asText();
            
            // 获取sourcePortID，这包含了重要的连接端口信息
            String sourcePortId = null;
            if (edgeJson.has("sourcePortID")) {
                sourcePortId = edgeJson.get("sourcePortID").asText();
            }
            
            log.debug("处理边关系[{}/{}]: {} -> {}, sourcePortId: {}", 
                    processedEdges, edgesArray.size(), sourceNodeId, targetNodeId, sourcePortId);
            
            NodeInfo sourceNode = nodeMap.get(sourceNodeId);
            NodeInfo targetNode = nodeMap.get(targetNodeId);
            
            if (sourceNode == null || targetNode == null) {
                log.warn("找不到节点信息，sourceNodeId: {}, targetNodeId: {}", sourceNodeId, targetNodeId);
                skippedEdges++;
                continue;
            }
            
            // 跳过start节点
            if ("start".equals(sourceNode.getNodeType()) || "start".equals(targetNode.getNodeType())) {
                log.debug("跳过start节点: {} -> {}", sourceNode.getNodeType(), targetNode.getNodeType());
                skippedEdges++;
                continue;
            }
            
            // 验证节点是否有有效的引用ID
            if (sourceNode.getRefId() == null || sourceNode.getRefId().trim().isEmpty() ||
                targetNode.getRefId() == null || targetNode.getRefId().trim().isEmpty()) {
                log.warn("节点缺少有效的引用ID，跳过关系: {}({}) -> {}({})", 
                        sourceNode.getNodeType(), sourceNode.getRefId(), 
                        targetNode.getNodeType(), targetNode.getRefId());
                skippedEdges++;
                continue;
            }
            
            log.info("创建配置关系: {}({}) -> {}({}), sourcePortId: {}", 
                    sourceNode.getNodeType(), sourceNode.getRefId(), 
                    targetNode.getNodeType(), targetNode.getRefId(), sourcePortId);
            
            // 生成配置关系，传入端口信息
            AiClientConfig config = createAiClientConfig(sourceNode, targetNode, sourcePortId);
            if (config != null) {
                configList.add(config);
                validConfigs++;
                log.info("成功创建配置关系[{}]: {} -> {}", validConfigs, config.getSourceType() + ":" + config.getSourceId(), config.getTargetType() + ":" + config.getTargetId());
            } else {
                skippedEdges++;
                log.warn("创建配置关系失败: {}({}) -> {}({})", 
                        sourceNode.getNodeType(), sourceNode.getRefId(), 
                        targetNode.getNodeType(), targetNode.getRefId());
            }
        }
        
        log.info("边关系解析完成，处理{}条边，跳过{}条，生成{}条有效配置关系", 
                processedEdges, skippedEdges, validConfigs);
    }

    /**
     * 创建AiClientConfig配置关系
     */
    private static AiClientConfig createAiClientConfig(NodeInfo sourceNode, NodeInfo targetNode, String sourcePortId) {
        // 确保两个节点都有引用ID
        if (sourceNode.getRefId() == null || targetNode.getRefId() == null) {
            log.warn("节点缺少引用ID，source: {}, target: {}", sourceNode, targetNode);
            return null;
        }
        
        // 构建扩展参数，包含端口信息和节点标题
        String extParam = "{}";
        try {
            StringBuilder extParamBuilder = new StringBuilder("{");
            boolean hasParam = false;
            
            // 添加源端口ID
            if (sourcePortId != null && !sourcePortId.trim().isEmpty()) {
                extParamBuilder.append("\"sourcePortId\":\"").append(sourcePortId).append("\"");
                hasParam = true;
            }
            
            // 添加源节点标题
            if (sourceNode.getTitle() != null && !sourceNode.getTitle().trim().isEmpty()) {
                if (hasParam) extParamBuilder.append(",");
                extParamBuilder.append("\"sourceTitle\":\"").append(sourceNode.getTitle()).append("\"");
                hasParam = true;
            }
            
            // 添加目标节点标题
            if (targetNode.getTitle() != null && !targetNode.getTitle().trim().isEmpty()) {
                if (hasParam) extParamBuilder.append(",");
                extParamBuilder.append("\"targetTitle\":\"").append(targetNode.getTitle()).append("\"");
                hasParam = true;
            }
            
            extParamBuilder.append("}");
            extParam = extParamBuilder.toString();
            
        } catch (Exception e) {
            log.warn("构建扩展参数失败，使用默认值: {}", e.getMessage());
            extParam = "{}";
        }
        
        AiClientConfig config = AiClientConfig.builder()
                .sourceType(sourceNode.getNodeType())
                .sourceId(sourceNode.getRefId())
                .targetType(targetNode.getNodeType())
                .targetId(targetNode.getRefId())
                .extParam(extParam)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
                
        log.debug("创建配置对象: sourceType={}, sourceId={}, targetType={}, targetId={}, extParam={}", 
                config.getSourceType(), config.getSourceId(), config.getTargetType(), config.getTargetId(), config.getExtParam());
                
        return config;
    }

    /**
     * 验证和统计解析结果
     */
    private static void validateAndLogResults(Map<String, NodeInfo> nodeMap, List<AiClientConfig> configList, int totalEdges) {
        log.info("=== 解析结果统计 ===");
        
        // 统计节点类型
        Map<String, Integer> nodeTypeCount = new HashMap<>();
        Map<String, Integer> nodeWithRefIdCount = new HashMap<>();
        
        for (NodeInfo node : nodeMap.values()) {
            String nodeType = node.getNodeType();
            nodeTypeCount.put(nodeType, nodeTypeCount.getOrDefault(nodeType, 0) + 1);
            
            if (node.getRefId() != null && !node.getRefId().trim().isEmpty()) {
                nodeWithRefIdCount.put(nodeType, nodeWithRefIdCount.getOrDefault(nodeType, 0) + 1);
            }
        }
        
        log.info("节点类型统计:");
        for (Map.Entry<String, Integer> entry : nodeTypeCount.entrySet()) {
            int withRefId = nodeWithRefIdCount.getOrDefault(entry.getKey(), 0);
            log.info("  {}: {}个节点，{}个有引用ID", entry.getKey(), entry.getValue(), withRefId);
        }
        
        // 统计配置关系类型
        Map<String, Integer> relationTypeCount = new HashMap<>();
        for (AiClientConfig config : configList) {
            String relationType = config.getSourceType() + " -> " + config.getTargetType();
            relationTypeCount.put(relationType, relationTypeCount.getOrDefault(relationType, 0) + 1);
        }
        
        log.info("配置关系统计:");
        for (Map.Entry<String, Integer> entry : relationTypeCount.entrySet()) {
            log.info("  {}: {}条关系", entry.getKey(), entry.getValue());
        }
        
        log.info("总计: {}个节点，{}条边，{}条有效配置关系", 
                nodeMap.size(), totalEdges, configList.size());
        log.info("=== 统计结束 ===");
    }

    /**
     * 节点信息内部类
     */
    private static class NodeInfo {
        private String nodeId;
        private String nodeType;
        private String title;
        private String refId;

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getNodeType() {
            return nodeType;
        }

        public void setNodeType(String nodeType) {
            this.nodeType = nodeType;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getRefId() {
            return refId;
        }

        public void setRefId(String refId) {
            this.refId = refId;
        }

        @Override
        public String toString() {
            return "NodeInfo{" +
                    "nodeId='" + nodeId + '\'' +
                    ", nodeType='" + nodeType + '\'' +
                    ", title='" + title + '\'' +
                    ", refId='" + refId + '\'' +
                    '}';
        }
    }
}