package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 长期记忆管理页分页响应。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryPageResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<LongTermMemoryResponseDTO> items;
    private long total;
    private int page;
    private int pageSize;
}
