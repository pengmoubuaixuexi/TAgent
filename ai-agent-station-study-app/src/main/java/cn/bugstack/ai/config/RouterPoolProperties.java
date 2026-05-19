package cn.bugstack.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由专用 ChatClient 池配置（P1 收尾）。
 * <p>
 * Intent / Strategy / Model / RAG / Tool / QueryRewriter / Summarizer 等"路由前置"类调用
 * 不需要 RAG advisor / ChatMemory / MCP 工具，仅按 tier 选模型即可。
 * <p>
 * 这里声明的每一项启动时都构建成纯 ChatClient（不挂 advisor，不挂 tool）注册到 Spring 上下文，
 * 同时写入 {@code ModelTierRegistry}，让 {@code AbstractExecuteSupport.getChatClientByTier}
 * 能真选出 small/medium/large 三档。
 * <p>
 * 用同一套 OpenAI 兼容代理（{@code spring.ai.openai.*}），所以这里只声明 model name 即可，
 * 不重复 base-url / api-key。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.router-pool")
public class RouterPoolProperties {

    /** 是否启用路由池装配 */
    private boolean enabled = false;

    /** 路由专用 API base-url；为空时回退 spring.ai.openai.base-url */
    private String baseUrl;
    /** 路由专用 API api-key；为空时回退 spring.ai.openai.api-key */
    private String apiKey;

    /** 路由 ChatClient 条目；启动时按列表顺序构建 */
    private List<Entry> entries = new ArrayList<>();

    @Data
    public static class Entry {
        /** ChatClient bean 后缀；最终 bean 名 = {@code ai_client_<id>} */
        private String id;
        /** 模型名（直接传 OpenAI 兼容代理） */
        private String model;
        /** small / medium / large */
        private String tier;
    }
}
