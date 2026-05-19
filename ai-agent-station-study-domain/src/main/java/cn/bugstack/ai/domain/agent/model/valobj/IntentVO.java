package cn.bugstack.ai.domain.agent.model.valobj;

import cn.bugstack.ai.domain.agent.model.valobj.enums.IntentCategoryEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图分类结果（P0.1.1）。
 * <p>
 * 走 shadow mode 时只用来打日志/MDC；切换到正式路由后由 StrategyRouter 消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentVO {

    private IntentCategoryEnumVO category;

    /** 0.0 - 1.0；low confidence (<0.6) 由调用方决定回退策略 */
    private double confidence;

    /** 模型原始返回，便于 bad-case 复盘；不参与业务逻辑 */
    private String rawResponse;

    /** 分类耗时（ms），shadow 模式下用来评估"加意图分类后总延迟" */
    private long latencyMs;

    public static IntentVO unknown(String reason) {
        return IntentVO.builder()
                .category(IntentCategoryEnumVO.UNKNOWN)
                .confidence(0.0)
                .rawResponse(reason)
                .latencyMs(0)
                .build();
    }
}
