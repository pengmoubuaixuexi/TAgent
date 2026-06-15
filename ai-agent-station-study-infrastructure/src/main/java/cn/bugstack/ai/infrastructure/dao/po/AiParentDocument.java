package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiParentDocument {
    private Long id;
    private String parentId;
    private String content;
    private String knowledgeTag;
    private String source;
    /** 第 61 轮 Phase 2：LLM 生成的精炼小标题（5-15 字），用于前端引用依据卡片 */
    private String title;
    private String userId;
    private Date createdAt;
}
