package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天模型配置，值对象
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/27 17:43
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientModelVO {

    /**
     * 全局唯一模型ID
     */
    private String modelId;

    /**
     * 关联的API配置ID
     */
    private String apiId;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 模型类型：openai、deepseek、claude
     */
    private String modelType;

    /**
     * 档位（P0.1.3 Model Router）：small / medium / large；缺省 medium
     */
    private String tier;

    /** JSON capability descriptor, for example TEXT+IMAGE input support. */
    private String capabilitiesJson;

    public boolean supportsImageInput() {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) return false;
        try {
            com.alibaba.fastjson.JSONObject value = com.alibaba.fastjson.JSON.parseObject(capabilitiesJson);
            java.util.List<String> modalities = value.getJSONArray("inputModalities") == null
                    ? java.util.List.of()
                    : value.getJSONArray("inputModalities").toJavaList(String.class);
            return modalities.stream().anyMatch("IMAGE"::equalsIgnoreCase);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 工具 mcp ids
     */
    private List<String> toolMcpIds;

}
