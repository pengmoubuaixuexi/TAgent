package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * P2.6 15.2 用户反馈：thumbs up/down + 可选评论。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentFeedbackRequestDTO {

    /** SSE 末尾返回的 traceId，用于关联本次对话 */
    private String traceId;

    /** 会话 ID */
    private String sessionId;

    /** thumbs_up / thumbs_down */
    private String rating;

    /** 可选文字反馈 */
    private String comment;
}
