package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Provider-neutral image input carried by a user message.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatImageInputDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** URL or BASE64. */
    private String sourceType;

    /** HTTP(S) image URL when sourceType is URL. */
    private String url;

    /** data:image/...;base64,... when sourceType is BASE64. */
    private String dataUrl;

    private String name;

    private String mimeType;

    private Long size;
}
