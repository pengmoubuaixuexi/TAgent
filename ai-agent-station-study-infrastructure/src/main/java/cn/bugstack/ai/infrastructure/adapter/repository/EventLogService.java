package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.service.execute.EventLogEntry;
import cn.bugstack.ai.domain.agent.service.execute.IEventLogService;
import cn.bugstack.ai.infrastructure.dao.IAiEventLogDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEventLog;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * P2.8 17.2 事件日志基础设施实现。V035 (2026-05-14)：加 userId / tenantId / agentId。
 */
@Slf4j
@Service
public class EventLogService implements IEventLogService {

    @Value("${agent.event-log.enabled:false}")
    private boolean enabled;

    @Resource
    private IAiEventLogDao aiEventLogDao;

    @Override
    public void log(EventLogEntry entry) {
        if (!enabled) return;
        if (entry == null) return;
        try {
            AiEventLog po = AiEventLog.builder()
                    .sessionId(entry.getSessionId())
                    .userId(entry.getUserId())
                    .tenantId(entry.getTenantId())
                    .agentId(entry.getAgentId())
                    .billingScope(entry.getBillingScope())
                    .stepName(entry.getStepName())
                    .stepIndex(entry.getStepIndex())
                    .inputPrompt(entry.getInputPrompt())
                    .outputText(entry.getOutputText())
                    .model(entry.getModel())
                    .promptTokens(entry.getPromptTokens())
                    .completionTokens(entry.getCompletionTokens())
                    .latencyMs(entry.getLatencyMs())
                    .build();
            aiEventLogDao.insert(po);
        } catch (Exception e) {
            // 2026-05-28：原来只打 e.getMessage()，但部分异常(如某些 DataIntegrity 包装)message 为空 → err= 空、无法定位。
            // 改成带 inputPrompt 字符数 + 完整异常堆栈，下次插入失败一眼能看到真因（列溢出/约束/类型等）。
            log.warn("EventLog insert failed: sessionId={} stepName={} model={} inputPromptChars={} outputChars={}",
                    entry.getSessionId(), entry.getStepName(), entry.getModel(),
                    entry.getInputPrompt() == null ? 0 : entry.getInputPrompt().length(),
                    entry.getOutputText() == null ? 0 : entry.getOutputText().length(), e);
        }
    }
}
