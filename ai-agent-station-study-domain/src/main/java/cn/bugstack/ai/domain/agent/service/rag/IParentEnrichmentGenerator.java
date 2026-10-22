package cn.bugstack.ai.domain.agent.service.rag;

import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;

/**
 * 入库增强：为一个 parent 生成小标题，并为它的每个 child 生成检索上下文。
 * <p>
 * 一个 parent 至多一次模型调用（JSON 无法解析时再修复一次），模型能看到同一 parent 下的全部 child，
 * 因此上下文可以写清"该方法 / 上述实验"等指代。失败时降级为无标题、无上下文，不中断入库。
 */
public interface IParentEnrichmentGenerator {

    /**
     * @param documentTitle 原始文件名
     * @param children      同一 parent 切出的 child，按顺序排列
     * @return title 可为 null；contexts 与 children 一一对应，单个元素可为 null
     */
    Enrichment enrich(String documentTitle, List<Document> children);

    /** 当前增强策略标识；参与断点续做指纹，策略变化后不复用旧片段。 */
    String strategy();

    /**
     * 成功增强的 parent 是否一定带标题。为 true 时，标题为空的 parent 视为增强失败，
     * 断点续做不会跳过它，而是重新增强。
     */
    default boolean titleRequired() { return false; }

    /** degraded = 本应调用模型增强但失败，已降级为无标题、无上下文。 */
    record Enrichment(String title, List<String> contexts, boolean degraded) {
        public Enrichment(String title, List<String> contexts) { this(title, contexts, false); }

        public static Enrichment none(int size) {
            return new Enrichment(null, Collections.nCopies(size, null), false);
        }

        public static Enrichment failed(int size) {
            return new Enrichment(null, Collections.nCopies(size, null), true);
        }
    }

    /** 增强模型整体不可用（未配置、模型不存在、鉴权失败等），同一文件的其余 parent 不必再调用。 */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
