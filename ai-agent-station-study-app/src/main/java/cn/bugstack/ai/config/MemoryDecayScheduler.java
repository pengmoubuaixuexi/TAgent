package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.agent.service.memory.longterm.ILongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P2.1 10.2 记忆遗忘衰减：定期归档冷记忆。
 * <p>
 * 双因子打分：last_accessed（距今多少天）+ access_count（被召回次数）。
 * 每天凌晨 3 点扫描，把 staleDays 天未访问且访问次数低于 minAccess 的记录归档。
 * <p>
 * 支持通过配置开关：{@code agent.memory.decay.enabled=true}
 */
@Slf4j
@Component
// Claude 改进：默认 OFF。原 matchIfMissing=true 会让任何未配置的环境上线即触发归档，
// 风险是把测试期累积的"真实但低频"记忆（譬如新接入的用户）连同噪声一起归档。
// 强制要求显式 agent.memory.decay.enabled=true 才启动调度。
@ConditionalOnProperty(prefix = "agent.memory.decay", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MemoryDecayScheduler {

    private final ILongTermMemoryService ltmService;

    public MemoryDecayScheduler(ILongTermMemoryService ltmService) {
        this.ltmService = ltmService;
    }

    /** 每天凌晨 3:07 执行 */
    @Scheduled(cron = "0 7 3 * * ?")
    public void runDecay() {
        try {
            int count = ltmService.runDecay(30, 3, 200);
            if (count > 0) {
                log.info("[MemoryDecay] archived {} stale memories", count);
            }
        } catch (Exception e) {
            log.error("[MemoryDecay] decay failed: {}", e.getMessage(), e);
        }
    }
}
