package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MCP tool catalog entry used for dynamic tool discovery.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiMcpToolCatalogVO {

    private Long id;

    private String mcpId;

    private String mcpName;

    private String toolName;

    private String toolDescription;

    private String toolDescriptionZh;

    /** doc2query：典型用户意图 / 示例查询 / 同义能力词（中文），供 BM25 召回桥接词汇鸿沟。 */
    private String toolIntentZh;

    private String inputSchemaJson;

    private Integer enabled;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
