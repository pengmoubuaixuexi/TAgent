package cn.bugstack.ai.domain.agent.service.execute.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional operator-verified context windows; unknown providers use the global input ceiling. */
@Component
@ConfigurationProperties(prefix = "agent.context")
public class ModelContextSettings {
    private Map<String, Integer> modelContextWindows = new LinkedHashMap<>();
    public Map<String, Integer> getModelContextWindows() { return modelContextWindows; }
    public void setModelContextWindows(Map<String, Integer> values) {
        modelContextWindows = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }
}
