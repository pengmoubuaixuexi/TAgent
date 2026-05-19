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
            log.warn("EventLog insert failed: sessionId={} stepName={} model={} err={}",
                    entry.getSessionId(), entry.getStepName(), entry.getModel(), e.getMessage());
        }
    }
}
