package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.enums.ModelTierEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模型档位注册表（P0.1.3 Model Router）。
 * <p>
 * 装配阶段（{@code AiClientNode}）每注册一个 ChatClient 就调 {@link #register(String, ModelTierEnumVO)}
 * 把 clientId 跟它的 model tier 关联起来。运行时 step 节点用 {@link #pickClientIdByTier(ModelTierEnumVO)}
 * 找到对应档位的 clientId（命中多个时返回第一个，足够 MVP）。
 * <p>
 * 设计选择：
 * <ul>
 *   <li>用 ConcurrentHashMap + CopyOnWriteArrayList 保证并发读写安全：register() 在启动期调用，读方法在请求期调用</li>
 *   <li>register 是幂等的（同 clientId 反复注册取最后一次的 tier）；支持 armory 重新装配</li>
 *   <li>未注册的 tier 返回空列表，调用方自行 fallback；不抛异常以便 step 安全回退</li>
 * </ul>
 */
@Slf4j
@Component
public class ModelTierRegistry {

    /** clientId → tier，反向索引主索引；下面的 byTier 由它派生 */
    private final Map<String, ModelTierEnumVO> clientTier = new ConcurrentHashMap<>();

    /** tier → 该档位下的 clientId 列表（按注册顺序） */
    private final Map<ModelTierEnumVO, List<String>> byTier = new ConcurrentHashMap<>();

    public void register(String clientId, ModelTierEnumVO tier) {
        if (clientId == null || clientId.isBlank() || tier == null) return;

        // 老 tier 先从 byTier 摘掉，避免同 client 在多个 tier 列表里出现
        ModelTierEnumVO old = clientTier.put(clientId, tier);
        if (old != null && old != tier) {
            byTier.computeIfPresent(old, (k, v) -> { v.remove(clientId); return v; });
        }
        byTier.computeIfAbsent(tier, k -> new CopyOnWriteArrayList<>())
                .removeIf(clientId::equals); // 防重复
        byTier.get(tier).add(clientId);
        log.debug("ModelTierRegistry register: clientId={} tier={}", clientId, tier.getCode());
    }

    /** 取该档位下第一个注册的 clientId；空则返回 null（让调用方走 fallback） */
    public String pickClientIdByTier(ModelTierEnumVO tier) {
        if (tier == null) return null;
        List<String> list = byTier.get(tier);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    /** 取该档位下所有 clientId，调用方可以做更复杂的选择（比如 round-robin / 按 cost 排序） */
    public List<String> clientIdsByTier(ModelTierEnumVO tier) {
        if (tier == null) return Collections.emptyList();
        List<String> list = byTier.get(tier);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    public ModelTierEnumVO tierOf(String clientId) {
        return clientId == null ? null : clientTier.get(clientId);
    }

    public int size() {
        return clientTier.size();
    }
}
