package cn.bugstack.ai.domain.agent.service.execute.common;

import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 把 Step 节点之间 DynamicContext 的演化（dataObjects keys / 关键产物 / executionHistory 长度）
 * 写一行到独立 logger {@code step.transition}，→ Logstash → ES。
 * <p>
 * 关键 key（analysisResult / executionResult / supervisionResult / reflexionCritique / finalSummary）
 * 会带摘要（截断到 {@value #VALUE_MAX_CHARS} 字符）一起落 ES，
 * 用 Kibana 按 {@code traceId:"Q27" AND logger:"step.transition"} 排序，
 * 就能直观看到一次请求 step1→2→3→4 之间状态是怎么接力的。
 */
public final class StepTransitionRecorder {

    private static final Logger LOG = LoggerFactory.getLogger("step.transition");
    private static final int VALUE_MAX_CHARS = 2_000;

    private StepTransitionRecorder() {}

    /**
     * @param fromStep         哪个 step 在调用本方法（写在日志的 transitionFrom MDC 字段）
     * @param dataObjects      DynamicContext.getDataObjects()
     * @param executionHistory DynamicContext.getExecutionHistory()
     * @param step             当前 step 序号
     * @param completed        任务是否已完成（影响是否进入 Step4 汇总）
     */
    public static void record(String fromStep, Map<String, Object> dataObjects,
                              StringBuilder executionHistory, int step, boolean completed) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("step", step);
            snap.put("completed", completed);
            Set<String> keys = dataObjects == null ? Set.of() : dataObjects.keySet();
            snap.put("dataObjectKeys", keys);
            snap.put("executionHistoryChars", executionHistory == null ? 0 : executionHistory.length());
            // 关键产物摘要：每个常见 key 截断后填入
            snap.put("analysisResult", abbrev(dataObjects, "analysisResult"));
            snap.put("executionResult", abbrev(dataObjects, "executionResult"));
            snap.put("supervisionResult", abbrev(dataObjects, "supervisionResult"));
            snap.put("reflexionCritique", abbrev(dataObjects, "reflexionCritique"));
            snap.put("reflexionRetries", dataObjects == null ? null : dataObjects.get("reflexionRetries"));
            snap.put("finalSummary", abbrev(dataObjects, "finalSummary"));

            MDC.put("transitionFrom", fromStep);
            try {
                LOG.info("step.transition from={} step={} keys={} historyChars={} snapshot={}",
                        fromStep, step, keys, snap.get("executionHistoryChars"),
                        JSON.toJSONString(snap));
            } finally {
                MDC.remove("transitionFrom");
            }
        } catch (Exception e) {
            LOG.debug("step.transition record failed for {}: {}", fromStep, e.getMessage());
        }
    }

    private static String abbrev(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v == null) return null;
        String s = v.toString();
        if (s.length() > VALUE_MAX_CHARS) {
            return s.substring(0, VALUE_MAX_CHARS) + "...(truncated, full=" + s.length() + ")";
        }
        return s.replace("\r", "").replace("\n", "\\n");
    }
}
