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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI API配置节点
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/1 07:09
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport {

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

    /** 立即回答 finalize"真·关思考"时注入出站请求体的参数 JSON；空=不注入（零影响）。MiMo 官方 API 关思考参数因 serving 而异，做成可配置。 */
    @org.springframework.beans.factory.annotation.Value("${agent.no-think.body-params:}")
    private String noThinkBodyParams;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，API 接口请求{}", JSON.toJSONString(requestParameter));

        List<AiClientApiVO> aiClientApiList = dynamicContext.getValue(dataName());

        if (aiClientApiList == null || aiClientApiList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client api");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientApiVO aiClientApiVO : aiClientApiList) {
            // 2026-05-29：连接全部来自 DB ai_client_api（已去掉 agent.llm.* 全局覆盖）
            String effectiveBaseUrl = aiClientApiVO.getBaseUrl();
            String effectiveApiKey = aiClientApiVO.getApiKey();
            String completionsPath = OpenAiCompatibleApiSupport.chatCompletionsPath(effectiveBaseUrl, aiClientApiVO.getCompletionsPath());
            String embeddingsPath = OpenAiCompatibleApiSupport.embeddingsPath(effectiveBaseUrl, aiClientApiVO.getEmbeddingsPath());
            // 构建 OpenAiApi（WebClient 注入两个 filter）：
            //   1. ReasoningContentFilter：回传 mimo thinking mode 的 reasoning_content
            //   2. WireTraceRecorder：抓取 advisor 注入后的最终请求体 + 原始响应体到 ES（logger=llm.wire）
            // 顺序很重要：reasoning 先注入 → wire 再 log，看到的就是真正发出去的 body
            ReasoningContentFilter reasoningFilter = new ReasoningContentFilter(noThinkBodyParams);
            cn.bugstack.ai.domain.agent.service.execute.common.WireTraceRecorder wireTraceRecorder =
                    new cn.bugstack.ai.domain.agent.service.execute.common.WireTraceRecorder();
            WebClient.Builder webClientBuilder = WebClient.builder()
                    .filter(reasoningFilter)
                    .filter(wireTraceRecorder);
            log.info("[AiClientApiNode] building OpenAiApi apiId={} baseUrl={} filterCount=2 (ReasoningContentFilter, WireTraceRecorder)",
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
