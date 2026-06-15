package cn.bugstack.ai.domain.agent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * G1 (F6) Human Approval Gate — A 阶段：sessionId → SSE emitter 反查通道。
 * <p>
 * <b>目的</b>：工具调用层（{@code MeteredToolCallback.call}）只持有 sessionId（来自 RuntimeContext / MDC），
 * 不持有 emitter。命中高风险工具需要走审批，或需要推送工具调用进度时，通过本 registry 反查当前 session
 * 的 emitter 才能 emit {@code human_approval_required} / {@code tool_call_*} SSE 事件。
 * <p>
 * <b>生命周期</b>（Codex 第 42 轮边界第 2 条）：调用方应在 SSE 建立时调 {@link #register}，
 * 并把 emitter 的 {@code onCompletion / onTimeout / onError} 回调里调 {@link #unregister} —
 * 避免会话结束后 emitter 引用常驻 map 造成内存泄漏。
 * <p>
 * <b>通道缺失策略</b>：{@link #get(String)} 找不到时返 null，调用方（{@code HumanApprovalGate}）
 * 应据此返结构化 {@code approval_unavailable} 错误，而不是阻塞或静默放行。
 */
@Slf4j
@Component
public class ApprovalChannelRegistry {

    private final ConcurrentHashMap<String, ResponseBodyEmitter> channels = new ConcurrentHashMap<>();

    /** 注册 sessionId → emitter。重复注册会替换旧 emitter（同 session 重连场景）。 */
    public void register(String sessionId, ResponseBodyEmitter emitter) {
        if (sessionId == null || sessionId.isBlank() || emitter == null) return;
        ResponseBodyEmitter prev = channels.put(sessionId, emitter);
        if (prev != null) {
            log.debug("[ApprovalChannel] replaced existing channel sessionId={}", sessionId);
        } else {
            log.debug("[ApprovalChannel] registered sessionId={}", sessionId);
        }
    }

    /**
     * 按引用注销（compare-and-remove）—— <b>emitter 回调（onCompletion / onTimeout / onError）必须用此 API</b>。
     * <p>
     * 修复 Codex 第 44 轮指出的并发误删 bug：旧 SSE 连接 A 已被新连接 B 替换后，A 的回调晚到不能把 B 删掉。
     * {@link java.util.concurrent.ConcurrentMap#remove(Object, Object)} 只在当前值 == emitter 时才删，
     * 引用不同（已被 B 替换）就跳过，避免误删。
     */
    public void unregister(String sessionId, ResponseBodyEmitter emitter) {
        if (sessionId == null || emitter == null) return;
        boolean removed = channels.remove(sessionId, emitter);
        if (removed) {
            log.debug("[ApprovalChannel] unregistered sessionId={} by ref", sessionId);
        } else {
            log.debug("[ApprovalChannel] skip unregister: sessionId={} already replaced by newer emitter", sessionId);
        }
    }

    /**
     * 按 sessionId 强制注销（不比对 emitter 引用）。
     * <p>
     * <b>仅用于主动清理</b>（例如运维 / 测试），<b>禁止</b>在 emitter 的 onCompletion/onTimeout/onError 里调用 ——
     * 那些回调必须用 {@link #unregister(String, ResponseBodyEmitter)} 防止误删并发替换的新 emitter。
     */
    public void unregister(String sessionId) {
        if (sessionId == null) return;
        ResponseBodyEmitter removed = channels.remove(sessionId);
        if (removed != null) {
            log.debug("[ApprovalChannel] unregistered sessionId={} (force)", sessionId);
        }
    }

    /** 反查；不存在返 null。 */
    public ResponseBodyEmitter get(String sessionId) {
        if (sessionId == null) return null;
        return channels.get(sessionId);
    }

    /** 仅供观测/审计：当前活跃通道数。 */
    public int activeCount() {
        return channels.size();
    }
}
