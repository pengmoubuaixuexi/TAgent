package cn.bugstack.ai.test.observe;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cn.bugstack.ai.domain.agent.service.execute.IEventLogService;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmCallContext;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmMetrics;
import cn.bugstack.ai.domain.agent.service.execute.common.LlmObservationRecorder;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class LlmObservationOwnershipTest {
    @Test public void esLogMdcUsesExplicitOwnerAndRestoresReusedThreadIdentity() {
        var recorder = new LlmObservationRecorder();
        ReflectionTestUtils.setField(recorder, "llmMetrics", mock(LlmMetrics.class));
        ReflectionTestUtils.setField(recorder, "eventLogService", mock(IEventLogService.class));
        Logger logger = (Logger) LoggerFactory.getLogger(LlmObservationRecorder.class);
        List<Map<String, String>> emitted = new ArrayList<>();
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getMessage().startsWith("LLM step completed")) emitted.add(Map.copyOf(event.getMDCPropertyMap()));
            }
        };
        appender.start();
        logger.addAppender(appender);
        MDC.put("userId", "previous-worker-owner");
        try {
            for (String user : List.of("alice", "bob")) {
                recorder.record(LlmCallContext.builder().userId(user).agentId("agent-" + user)
                        .sessionId("session-" + user).stepName("answer").model("fixture").resultText("ok").build(),
                        null, 10, null);
                assertEquals("previous-worker-owner", MDC.get("userId"));
            }
            assertEquals(2, emitted.size());
            assertEquals("alice", emitted.get(0).get("userId"));
            assertEquals("bob", emitted.get(1).get("userId"));
            assertEquals("agent-alice", emitted.get(0).get("agentId"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            MDC.clear();
        }
    }
}
