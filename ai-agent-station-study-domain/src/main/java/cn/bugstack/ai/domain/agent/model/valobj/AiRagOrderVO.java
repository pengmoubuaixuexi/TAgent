package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库订单
 * @author Fuzhengwei bugstack.cn @小傅哥
 * 2025-05-05 20:02
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiRagOrderVO {

    /**
     * 知识库 ID（DB 唯一键，由服务层生成）
     */
    private String ragId;

    /**
     * 知识库名称
     */
    private String ragName;

    /**
     * 知识标签
     */
    private String knowledgeTag;

    /**
     * P2.3 12.5 文件 SHA-256 哈希，幂等去重用
     */
    private String fileHash;

    /**
     * P2.3 12.5 文件原始大小（字节），增量索引判断用
     */
    private Long fileSize;

    /**
     * 多租户隔离：上传时来自 MDC / 请求头的 user_id（之前一直未透传到 DB）
     */
    private String userId;

}
