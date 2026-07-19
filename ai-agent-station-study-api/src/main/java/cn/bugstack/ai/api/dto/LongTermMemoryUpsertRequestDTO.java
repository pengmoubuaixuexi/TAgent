package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 长期记忆管理页的新增/纠正请求。用户身份不在 body 中传递。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryUpsertRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String content;
    private String topic;
}
