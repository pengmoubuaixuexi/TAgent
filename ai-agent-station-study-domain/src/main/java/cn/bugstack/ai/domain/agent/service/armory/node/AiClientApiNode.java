package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.execute.common.ReasoningContentFilter;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI API配置节点
 *
 * @author TAgent
 * 2025/7/1 07:09
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport {

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

    @Value("${agent.llm.base-url:}")
    private String llmBaseUrl;

    @Value("${agent.llm.api-key:}")
    private String llmApiKey;

    @Value("${agent.llm.completions-path:}")
    private String llmCompletionsPath;

    @Value("${agent.llm.embeddings-path:}")
    private String llmEmbeddingsPath;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，API 接口请求{}", JSON.toJSONString(requestParameter));

        List<AiClientApiVO> aiClientApiList = dynamicContext.getValue(dataName());

        if (aiClientApiList == null || aiClientApiList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client api");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientApiVO aiClientApiVO : aiClientApiList) {
            boolean llmOverride = OpenAiCompatibleApiSupport.hasText(llmBaseUrl)
                    || OpenAiCompatibleApiSupport.hasText(llmApiKey);
            String effectiveBaseUrl = OpenAiCompatibleApiSupport.valueOrDefault(llmBaseUrl, aiClientApiVO.getBaseUrl());
            String effectiveApiKey = OpenAiCompatibleApiSupport.valueOrDefault(llmApiKey, aiClientApiVO.getApiKey());
            String completionsPath = llmOverride
                    ? OpenAiCompatibleApiSupport.chatCompletionsPath(effectiveBaseUrl, llmCompletionsPath)
                    : OpenAiCompatibleApiSupport.chatCompletionsPath(effectiveBaseUrl, aiClientApiVO.getCompletionsPath());
            String embeddingsPath = llmOverride
                    ? OpenAiCompatibleApiSupport.embeddingsPath(effectiveBaseUrl, llmEmbeddingsPath)
                    : OpenAiCompatibleApiSupport.embeddingsPath(effectiveBaseUrl, aiClientApiVO.getEmbeddingsPath());
            // 构建 OpenAiApi（WebClient 注入 ReasoningContentFilter 以回传 mimo thinking mode 的 reasoning_content）
            ReasoningContentFilter reasoningFilter = new ReasoningContentFilter();
            WebClient.Builder webClientBuilder = WebClient.builder().filter(reasoningFilter);
            log.info("[AiClientApiNode] building OpenAiApi apiId={} baseUrl={} filterCount=1 (ReasoningContentFilter)",
                    aiClientApiVO.getApiId(), effectiveBaseUrl);
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(effectiveBaseUrl)
                    .apiKey(effectiveApiKey)
                    .completionsPath(completionsPath)
                    .embeddingsPath(embeddingsPath)
                    .webClientBuilder(webClientBuilder)
                    .build();

            // 注册 OpenAiApi Bean 对象
            registerBean(beanName(aiClientApiVO.getApiId()), OpenAiApi.class, openAiApi);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientToolMcpNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_API.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_API.getDataName();
    }

}
