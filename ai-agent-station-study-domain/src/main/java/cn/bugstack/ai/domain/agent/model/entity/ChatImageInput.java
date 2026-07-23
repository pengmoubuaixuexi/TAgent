package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider-neutral image input received from an API client.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatImageInput {

    private String sourceType;

    private String url;

    private String dataUrl;

    private String name;

    private String mimeType;

    private Long size;
}
