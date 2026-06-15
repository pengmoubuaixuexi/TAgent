package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 路由专用 ChatClient 池装配（P0.1.3 收尾 + P1 路由前置层支撑）。
 * <p>
 * Intent / Strategy / RAG / QueryRewriter / Summarizer 等"路由前置"调用不需要 advisor / tool /
 * memory，单独搭建一组干净的 ChatClient。{@code rag-router} / {@code intent-router} 通过 bean 名
 * {@code ai_client_<entry.id>}（如 {@code ai_client_router-small}）直接取用。
 * <p>
 * 2026-05-29 重构：彻底去掉 {@code agent.llm.*} 全局开关与共享连接。每个 entry 只声明
 * {@code id + tier}，模型名与连接全部按 tier 从 DB（{@code ai_client_model} / {@code ai_client_api}）解析：
 * <ul>
 *   <li>取该 tier 下所有启用 model（按 modelId 排序，select 第一个优先）；</li>
 *   <li>逐个尝试构建，某个 model 装配失败（api 缺失等）则试同档下一个；</li>
 *   <li>同档全部失败 → 升档（small→medium→large）继续；到 LARGE 仍失败则跳过该 entry。</li>
 * </ul>
 * 运行期上游故障的同档 failover 不在本期范围（见 codex 对话 v1 第一期/第二期划分）。
 * <p>
 * 装配时机：{@link ApplicationReadyEvent}（等 OpenAi auto-config / 数据源就绪后再访问 DB）。
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RouterPoolConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RouterPoolProperties props;

    @Resource
    private IAgentRepository agentRepository;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!props.isEnabled()) {
            log.info("RouterPool disabled, skip");
            return;
        }
        if (props.getEntries() == null || props.getEntries().isEmpty()) {
            log.info("RouterPool enabled but entries empty, skip");
            return;
        }

        // 一次性拉全部启用 model，按 tier 分组（同档按 modelId 升序，保证 "select 第一个" 稳定）
        Map<ModelTierEnumVO, List<AiClientModelVO>> modelsByTier = agentRepository.queryEnabledAiClientModelVOList()
                .stream()
                .sorted(Comparator.comparing(AiClientModelVO::getModelId))
                .collect(Collectors.groupingBy(m -> ModelTierEnumVO.fromCode(m.getTier())));

        for (RouterPoolProperties.Entry e : props.getEntries()) {
            try {
                buildAndRegister(e, modelsByTier);
            } catch (Exception ex) {
                log.warn("RouterPool entry {} 装配失败：{}", e.getId(), ex.getMessage());
            }
        }
    }

    /**
     * 按 entry.tier 选 model 并构建注册。同档逐个试、空档升档；全失败则跳过该 entry（仅 warn，不抛）。
     */
    private void buildAndRegister(RouterPoolProperties.Entry e, Map<ModelTierEnumVO, List<AiClientModelVO>> modelsByTier) {
        ModelTierEnumVO tier = ModelTierEnumVO.fromCode(e.getTier());
        for (ModelTierEnumVO t = tier; t != null; t = nextTierUp(t)) {
            List<AiClientModelVO> candidates = modelsByTier.get(t);
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            for (AiClientModelVO model : candidates) {
                try {
                    registerRouterClient(e, model);
                    if (t != tier) {
                        log.info("RouterPool entry {} 档位 {} 无可用 model，已升档到 {}", e.getId(), tier.getCode(), t.getCode());
                    }
                    return;
                } catch (Exception ex) {
                    log.warn("RouterPool entry {} 用 model {} 构建失败，试同档下一个：{}", e.getId(), model.getModelId(), ex.getMessage());
                }
            }
        }
        log.warn("RouterPool entry {} 在 tier {} 及更高档位均无可用 model，跳过", e.getId(), tier.getCode());
    }

    /** 用一个 DB model（含其 api 连接）构建干净 ChatClient（不挂 advisor / tool）并注册成 bean。 */
    private void registerRouterClient(RouterPoolProperties.Entry e, AiClientModelVO model) {
        List<AiClientApiVO> apis = agentRepository.queryAiClientApiVOListByApiIds(List.of(model.getApiId()));
        if (apis.isEmpty() || apis.get(0) == null || apis.get(0).getBaseUrl() == null) {
            throw new IllegalStateException("model " + model.getModelId() + " 的 apiId=" + model.getApiId() + " 无有效 api 配置");
        }
        AiClientApiVO api = apis.get(0);
        String baseUrl = api.getBaseUrl();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(api.getApiKey())
                .completionsPath(OpenAiCompatibleApiSupport.chatCompletionsPath(baseUrl, api.getCompletionsPath()))
                .embeddingsPath(OpenAiCompatibleApiSupport.embeddingsPath(baseUrl, api.getEmbeddingsPath()))
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model.getModelName())
                        .build())
                .build();

        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String beanName = AiAgentEnumVO.AI_CLIENT.getBeanName(e.getId());
        DefaultListableBeanFactory factory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        if (factory.containsBeanDefinition(beanName)) {
            factory.removeBeanDefinition(beanName);
            log.info("RouterPool {} 旧Bean已销毁，重新注册", beanName);
        }
        BeanDefinitionBuilder b = BeanDefinitionBuilder.genericBeanDefinition(ChatClient.class, () -> chatClient);
        BeanDefinition def = b.getRawBeanDefinition();
        def.setScope(BeanDefinition.SCOPE_SINGLETON);
        factory.registerBeanDefinition(beanName, def);

        log.info("RouterPool 装配 OK: id={} tier={} modelId={} model={} baseUrl={} beanName={}",
                e.getId(), e.getTier(), model.getModelId(), model.getModelName(), baseUrl, beanName);
    }

    /** 升档：small→medium→large；large 到顶返回 null。 */
    private ModelTierEnumVO nextTierUp(ModelTierEnumVO t) {
        switch (t) {
            case SMALL:
                return ModelTierEnumVO.MEDIUM;
            case MEDIUM:
                return ModelTierEnumVO.LARGE;
            default:
                return null;
        }
    }
}
