package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiClientRagOrder;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 知识库配置表 DAO
 * @author bugstack虫洞栈
 * @description 知识库配置表数据访问对象
 */
@Mapper
public interface IAiClientRagOrderDao {

    /**
     * 插入知识库配置
     * @param aiClientRagOrder 知识库配置对象
     * @return 影响行数
     */
    int insert(AiClientRagOrder aiClientRagOrder);

    /**
     * 根据ID更新知识库配置
     * @param aiClientRagOrder 知识库配置对象
     * @return 影响行数
     */
    int updateById(AiClientRagOrder aiClientRagOrder);

    /**
     * 根据知识库ID更新知识库配置
     * @param aiClientRagOrder 知识库配置对象
     * @return 影响行数
     */
    int updateByRagId(AiClientRagOrder aiClientRagOrder);

    /**
     * 根据ID删除知识库配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据知识库ID删除知识库配置
     * @param ragId 知识库ID
     * @return 影响行数
     */
    int deleteByRagId(String ragId);

    /**
     * 根据ID查询知识库配置
     * @param id 主键ID
     * @return 知识库配置对象
     */
    AiClientRagOrder queryById(Long id);

    /**
     * 根据知识库ID查询知识库配置
     * @param ragId 知识库ID
     * @return 知识库配置对象
     */
    AiClientRagOrder queryByRagId(String ragId);

    /**
     * 查询启用的知识库配置
     * @return 知识库配置列表
     */
    List<AiClientRagOrder> queryEnabledRagOrders();

    /**
     * 根据知识标签查询知识库配置
     * @param knowledgeTag 知识标签
     * @return 知识库配置列表
     */
    List<AiClientRagOrder> queryByKnowledgeTag(String knowledgeTag);

    /**
     * 查询所有知识库配置
     * @return 知识库配置列表
     */
    List<AiClientRagOrder> queryAll();

    /**
     * 按 SHA-256 + knowledge_tag 查重计数。
     * @param fileHash 文件 SHA-256
     * @param knowledgeTag 知识库标签
     * @return 命中条数（>0 即存在）
     */
    int countByFileHashAndTag(@org.apache.ibatis.annotations.Param("fileHash") String fileHash,
                               @org.apache.ibatis.annotations.Param("knowledgeTag") String knowledgeTag);

}