package cn.bugstack.ai.domain.agent.service.attachment;

import cn.bugstack.ai.domain.agent.service.execute.event.RunEventPublisher;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Run event {@code attachment_notice}: tells the user once per run when an attachment could not be used as sent. */
@Slf4j
@Component
public class AttachmentNotices {

    public static final String EVENT = "attachment_notice";
    public static final String IMAGE_UNSUPPORTED = "IMAGE_UNSUPPORTED";

    private final RunEventPublisher events;
    private final Cache<String, Boolean> sent = CacheBuilder.newBuilder()
            .expireAfterWrite(Duration.ofHours(2)).maximumSize(10_000).build();

    public AttachmentNotices(RunEventPublisher events) {
        this.events = events;
    }

    /** The configured model cannot read images, so the images reach it only as links. */
    public void imagesNotSupported(Map<String, Object> toolContext) {
        notice(toolContext, IMAGE_UNSUPPORTED,
                "当前模型不能直接看图，图片只以链接形式提供给它，回答可能没有用到图片内容。需要分析图片时，请选用支持看图的模型。");
    }

    private void notice(Map<String, Object> toolContext, String code, String message) {
        if (toolContext == null) return;
        String runId = text(toolContext.get("agent.run_id"));
        if (runId == null || sent.asMap().putIfAbsent(runId + "|" + code, Boolean.TRUE) != null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("code", code);
        payload.put("message", message);
        try {
            events.publishToRun(runId, text(toolContext.get("sessionId")), EVENT, payload);
        } catch (RuntimeException unavailable) {
            log.warn("attachment notice unavailable runId={} code={}: {}", runId, code, unavailable.toString());
        }
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
