package cn.bugstack.ai.domain.agent.service.memory.longterm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 长期记忆管理分页结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryPage {

    private List<LongTermMemoryItem> items;
    private long total;
    private int page;
    private int pageSize;
}
