package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenAI API配置，值对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/27 17:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientApiVO {

    /**
     * API ID
     */
    private String apiId;

    /**
     * 基础URL
     */
    private String baseUrl;

    /**
     * API密钥
     */
    private String apiKey;

    /**
     * 对话补全路径
     */
    private String completionsPath;

    /**
     * 嵌入向量路径
     */
    private String embeddingsPath;

}
