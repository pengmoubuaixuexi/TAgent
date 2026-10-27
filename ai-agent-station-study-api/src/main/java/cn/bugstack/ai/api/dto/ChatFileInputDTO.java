package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * One attachment of a user message: an uploaded file ({@code attachmentId}), or one of the
 * user's artifacts ({@code artifactId} with an optional {@code revision}; empty means current).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatFileInputDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String attachmentId;

    private String artifactId;

    private Integer revision;
}
