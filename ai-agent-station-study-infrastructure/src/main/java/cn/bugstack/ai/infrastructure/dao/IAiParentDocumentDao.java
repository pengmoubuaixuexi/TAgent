package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiParentDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiParentDocumentDao {

    int insert(AiParentDocument po);

    AiParentDocument findByParentId(@Param("parentId") String parentId);

    List<AiParentDocument> findByParentIds(@Param("parentIds") List<String> parentIds);

    int deleteByKnowledgeTag(@Param("knowledgeTag") String knowledgeTag);
}
