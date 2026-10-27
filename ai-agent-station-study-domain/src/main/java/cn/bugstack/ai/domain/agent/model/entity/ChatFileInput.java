package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One attachment selected in the composer: an uploaded file id, or an artifact and its revision. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatFileInput {

    private String attachmentId;

    private String artifactId;

    /** Null or 0 means the artifact's current revision at send time. */
    private Integer revision;
}
