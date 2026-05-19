package cn.bugstack.ai.domain.agent.model.entity.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Step3 质量评审结构化输出（P0.3）。
 * <p>
 * 给 P1 的 Reflexion 自反思回退用：{@code passed=false} 时把 {@code critique} 喂回 Step2 重做。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Step3QualityReview {

    /** 评审是否通过 */
    @JsonProperty("passed")
    private boolean passed;

    /** 0-100 评分 */
    @JsonProperty("score")
    private int score;

    /** 不通过时的 critique（喂回 Step2 重做） */
    @JsonProperty("critique")
    private String critique;

    /** 改进建议（结构化） */
    @JsonProperty("suggestions")
    private List<String> suggestions;
}
