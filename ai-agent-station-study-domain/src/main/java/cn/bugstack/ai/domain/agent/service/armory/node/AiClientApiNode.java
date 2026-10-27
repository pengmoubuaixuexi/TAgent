package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.service.armory.node.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
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
    /** 连接装配（含请求层模型降级、统一重试）；立即回答关思考参数 agent.no-think.body-params 也在这里注入。 */
    @org.springframework.beans.factory.annotation.Autowired
    private cn.bugstack.ai.domain.agent.service.model.failover.ModelConnections modelConnections;

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

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
            // 2026-10-24：装配移到 ModelConnectionAssembler，顺序不变：
            //   WebClient：模型降级 → ReasoningContentFilter（回传 reasoning_content）→ 鉴权 → WireTraceRecorder（ES llm.wire）
            //   RestClient：模型降级 → 鉴权 → ModelRequestGuard（上下文与预算、用量记账）
            // 降级在每一次 HTTP 请求上生效，包括工具循环里的后续请求。
            log.info("[AiClientApiNode] building OpenAiApi apiId={} baseUrl={} (failover, ReasoningContentFilter, Credentials, WireTraceRecorder)",
                    aiClientApiVO.getApiId(), aiClientApiVO.getBaseUrl());
            OpenAiApi openAiApi = modelConnections.assemble(aiClientApiVO);

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
