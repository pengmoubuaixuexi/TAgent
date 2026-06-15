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
 * 这里声明的每一项启动时都构建成纯 ChatClient（不挂 advisor，不挂 tool）注册到 Spring 上下文。
 * <p>
 * 2026-05-29 重构：条目只声明 {@code id + tier}，模型与连接全部来自 DB 的
 * {@code ai_client_model} / {@code ai_client_api}（RouterPoolConfig 按 tier 选 model，
 * 空档自动升档）。不再写裸 model name，也不再有 base-url / api-key（彻底去掉 agent.llm.* 全局开关）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.router-pool")
public class RouterPoolProperties {

    /** 是否启用路由池装配 */
    private boolean enabled = false;

    /** 路由 ChatClient 条目；启动时按列表顺序构建 */
    private List<Entry> entries = new ArrayList<>();

    @Data
    public static class Entry {
        /** ChatClient bean 后缀；最终 bean 名 = {@code ai_client_<id>} */
        private String id;
        /** small / medium / large；按此档从 DB ai_client_model 选模型，空档自动升档 */
        private String tier;
    }
}
