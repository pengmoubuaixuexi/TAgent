package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.service.IArmoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * P2.8 17.3 配置热加载：定时扫描 ai_client_* 表的最新 update_time，
 * 与上次内存快照对比，有变化则触发 armory reload。
 * <p>
 * 实现方式：每 30s 查一次 MAX(update_time)，与上次记录对比。
 * 不锁表、不增量监听——简单轮询够用，30s 延迟在可接受范围。
 */
@Slf4j
@Component
public class ConfigHotReloadListener {

    @Value("${agent.hot-reload.enabled:false}")
    private boolean enabled;

    @Resource
    private IArmoryService armoryService;

    private volatile long lastMaxUpdateEpoch = 0L;

    @Scheduled(fixedDelayString = "${agent.hot-reload.interval-ms:30000}")
    public void scanAndReload() {
        if (!enabled) return;

        try {
            long currentMax = queryMaxUpdateTime();
            if (currentMax > lastMaxUpdateEpoch) {
                log.info("[HotReload] Config change detected: last={} current={}",
                        epochToLocal(lastMaxUpdateEpoch), epochToLocal(currentMax));
                lastMaxUpdateEpoch = currentMax;
                // 触发全部 agent 重建（按 agentId 逐个装配）
                armoryService.reloadAll();
            }
        } catch (Exception e) {
            log.warn("[HotReload] scan failed: {}", e.getMessage());
        }
    }

    /**
     * 查询所有 ai_client 配置表的最大 update_time。
     * 失败返回当前时间戳（跳过本次，等下一轮再试）。
     */
    private long queryMaxUpdateTime() {
        try {
            Long max = armoryService.getMaxConfigUpdateTime();
            if (max != null) {
                long epoch = max;
                if (lastMaxUpdateEpoch == 0L) {
                    lastMaxUpdateEpoch = epoch; // 首次启动：记录基线，不触发 reload
                    log.info("[HotReload] baseline epoch={}", epochToLocal(epoch));
                }
                return epoch;
            }
        } catch (Exception e) {
            log.debug("[HotReload] queryMaxUpdateTime failed: {}", e.getMessage());
        }
        return lastMaxUpdateEpoch; // 保持上次值，不变不触发
    }

    private static LocalDateTime epochToLocal(long epoch) {
        if (epoch <= 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault());
    }

    public void markReloaded() {
        this.lastMaxUpdateEpoch = queryMaxUpdateTime();
    }
}
