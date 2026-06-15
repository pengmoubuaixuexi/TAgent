package cn.bugstack.ai.domain.agent.model.valobj;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * AI客户端配置，值对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/27 18:51
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientVO {

    /**
     * 客户端ID
     */
    private String clientId;

    /**
     * 客户端名称
     */
    private String clientName;

    /**
     * 描述
     */
    private String description;

    /**
     * 全局唯一模型ID
     */
    private String modelId;

    /**
     * Prompt ID List
     */
    private List<String> promptIdList;

    /**
     * MCP ID List
     */
    private List<String> mcpIdList;

    /**
     * 顾问ID List
     */
    private List<String> advisorIdList;

    public String getModelBeanName() {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId);
    }

    public List<String> getMcpBeanNameList() {
        List<String> mcpBeanNameList = new ArrayList<>();
        for (String mcpId : mcpIdList) {
            mcpBeanNameList.add(AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId));
        }
        return mcpBeanNameList;
    }

    public List<String> getAdvisorBeanNameList() {
        List<String> advisorBeanNameList = new ArrayList<>();
        for (String advisorId : advisorIdList) {
            advisorBeanNameList.add(AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(advisorId));
        }
        return advisorBeanNameList;
    }

}
