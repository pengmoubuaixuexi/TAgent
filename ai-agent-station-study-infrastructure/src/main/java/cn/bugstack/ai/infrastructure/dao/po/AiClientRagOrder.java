package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库配置表
 * @author bugstack虫洞栈
 * @description 知识库配置表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientRagOrder {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 知识库ID
     */
    private String ragId;

    /**
     * 多租户隔离：上传文件时记录的 user_id（DDL 已有列，应用层之前漏写）
     */
    private String userId;

    /**
     * 知识库名称
     */
    private String ragName;

    /**
     * 知识标签
     */
    private String knowledgeTag;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * P2.3 12.5 文件 SHA-256 哈希（幂等去重用）
     */
    private String fileHash;

    /**
     * P2.3 12.5 文件原始大小（字节）
     */
    private Long fileSize;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}