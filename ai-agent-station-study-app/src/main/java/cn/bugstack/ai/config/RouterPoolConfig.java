package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import cn.bugstack.ai.domain.agent.service.router.ModelTierRegistry;
import cn.bugstack.ai.domain.agent.service.support.OpenAiCompatibleApiSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 路由专用 ChatClient 池装配（P0.1.3 收尾 + P1 路由前置层支撑）。
 * <p>
 * Intent / Strategy / RAG / Tool / QueryRewriter / Summarizer 等"路由前置"调用不需要
 * advisor / tool / memory，单独搭建一组干净的 ChatClient，按 tier 注册到
 * {@link ModelTierRegistry}，让 {@code AbstractExecuteSupport.getChatClientByTier} 真能
 * 跨档位选模型。
 * <p>
 * 装配时机：{@link ApplicationReadyEvent}。原因：要等 PgVectorStore / OpenAi auto-config
 * 完成后再访问 {@code spring.ai.openai.*} 配置。
 * <p>
 * Bean 命名：{@code ai_client_<entry.id>}（沿用 {@link AiAgentEnumVO#AI_CLIENT} 前缀），
 * {@code ModelRouter} / {@code IntentRouter} / {@code QueryRewriter} 用同一套 lookup 路径。
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RouterPoolConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ModelTierRegistry modelTierRegistry;

    @Resource
    private RouterPoolProperties props;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${agent.llm.base-url:}")
    private String llmBaseUrl;

    @Value("${agent.llm.api-key:}")
    private String llmApiKey;

    @Value("${agent.llm.model:}")
    private String llmModel;

    @Value("${agent.llm.completions-path:}")
    private String llmCompletionsPath;

    @Value("${agent.llm.embeddings-path:}")
    private String llmEmbeddingsPath;

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

        String effectiveBaseUrl = OpenAiCompatibleApiSupport.valueOrDefault(
                llmBaseUrl, OpenAiCompatibleApiSupport.valueOrDefault(props.getBaseUrl(), baseUrl));
        String effectiveApiKey = OpenAiCompatibleApiSupport.valueOrDefault(
                llmApiKey, OpenAiCompatibleApiSupport.valueOrDefault(props.getApiKey(), apiKey));

        OpenAiApi sharedApi = OpenAiApi.builder()
                .baseUrl(effectiveBaseUrl)
                .apiKey(effectiveApiKey)
                .completionsPath(OpenAiCompatibleApiSupport.chatCompletionsPath(effectiveBaseUrl, llmCompletionsPath))
                .embeddingsPath(OpenAiCompatibleApiSupport.embeddingsPath(effectiveBaseUrl, llmEmbeddingsPath))
                .build();

        for (RouterPoolProperties.Entry e : props.getEntries()) {
            try {
                buildAndRegister(e, sharedApi);
            } catch (Exception ex) {
                log.warn("RouterPool entry {} 装配失败：{}", e.getId(), ex.getMessage());
            }
        }
    }

    private void buildAndRegister(RouterPoolProperties.Entry e, OpenAiApi sharedApi) {
        String beanName = AiAgentEnumVO.AI_CLIENT.getBeanName(e.getId());
        String effectiveModel = OpenAiCompatibleApiSupport.valueOrDefault(e.getModel(), llmModel);

        // 如果Bean已存在，先销毁再注册（支持热重载）
        DefaultListableBeanFactory factory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        if (factory.containsBeanDefinition(beanName)) {
            factory.removeBeanDefinition(beanName);
            log.info("RouterPool {} 旧Bean已销毁，重新注册", beanName);
        }

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(sharedApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(effectiveModel)
                        .build())
                .build();

        ChatClient chatClient = ChatClient.builder(chatModel).build();

        BeanDefinitionBuilder b = BeanDefinitionBuilder.genericBeanDefinition(ChatClient.class, () -> chatClient);
        BeanDefinition def = b.getRawBeanDefinition();
        def.setScope(BeanDefinition.SCOPE_SINGLETON);
        factory.registerBeanDefinition(beanName, def);

        ModelTierEnumVO tier = ModelTierEnumVO.fromCode(e.getTier());
        modelTierRegistry.register(e.getId(), tier);

        log.info("RouterPool 装配 OK: id={} model={} tier={} beanName={}",
                e.getId(), effectiveModel, tier.getCode(), beanName);
    }
}
