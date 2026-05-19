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
    private String userId;
    private Date createdAt;
}
