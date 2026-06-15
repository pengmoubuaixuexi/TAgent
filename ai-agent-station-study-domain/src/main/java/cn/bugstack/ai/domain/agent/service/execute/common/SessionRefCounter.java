package cn.bugstack.ai.domain.agent.service.execute.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第 61 轮新增：sessionId 维度的 RAG 引用累加计数器。
 * <p>
 * <b>解决问题</b>：自动执行模式下 Step2 多 client 各自触发 RAG，每个 advisor 实例从 [1] 重新编号，
 * 导致：(a) 答案中 client A 的 [1][2] 跟 client B 的 [1][2] 在前端被一张大卡覆盖找不到；
 * (b) 引用语义与展示对不上。
 * <p>
 * <b>方案</b>：sessionId → AtomicInteger，每次 RAG 检索取当前值作 startRef，提交 prompt 和 SSE
 * 都用全局 ref（[1][2][3][4][5]）；模型在每个 client prompt 里看到的就是连续编号，
 * 不需要改 prompt 模板让模型"接力编号"，模型只看自己 prompt 里给的编号是啥就用啥。
 * <p>
 * <b>清理</b>：会话生命周期通常 10-30 分钟，counter 体量极小（每 session 几十字节）。
 * 简化版用 {@link ConcurrentHashMap}，依靠 sessionId 自然过期 + 容量上限做兜底防泄漏。
 * 高阶版可替换为 Caffeine expireAfterWrite，先不做以免引入依赖。
 */
@Slf4j
@Component
public class SessionRefCounter {

    /** 防内存泄漏的硬上限；正常每 session 几十字节，10w session ≈ 几 MB */
    private static final int MAX_SESSIONS = 100_000;

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 预留 count 个引用编号，返回 startRef（下一个可用编号，1-based）。
     * <p>示例：第一次 advance(sessionId, 3) 返回 1（表示 [1][2][3]）；
     * 第二次 advance(sessionId, 2) 返回 4（表示 [4][5]）。</p>
     *
     * @param sessionId 会话 ID；null/blank → 退化为不累加，每次返回 1
     * @param count     本批要预留的编号数量
     * @return startRef，从 1 开始
     */
    public int advance(String sessionId, int count) {
        if (count <= 0) return 1;
        if (sessionId == null || sessionId.isBlank()) return 1;
        // 容量上限保护
        if (counters.size() >= MAX_SESSIONS && !counters.containsKey(sessionId)) {
            log.warn("[SessionRefCounter] sessions exceed {} hard limit, refusing new; sessionId={}", MAX_SESSIONS, sessionId);
            return 1;
        }
        AtomicInteger c = counters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        // 原子地取当前值并加上 count，得到本批 startRef = 旧值+1
        return c.getAndAdd(count) + 1;
    }

    /**
     * 显式清理某个 session 的计数器，会话结束时调用即可。
     * 调用方未显式清理也不会泄漏到雪崩 —— MAX_SESSIONS 保底。
     */
    public void clear(String sessionId) {
        if (sessionId == null) return;
        counters.remove(sessionId);
    }

    /** 仅测试用：观察当前已分配的总数。 */
    int peek(String sessionId) {
        AtomicInteger c = counters.get(sessionId);
        return c == null ? 0 : c.get();
    }
}
