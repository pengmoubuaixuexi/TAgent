package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiMcpToolCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP tool catalog DAO.
 */
@Mapper
public interface IAiMcpToolCatalogDao {

    List<AiMcpToolCatalog> queryEnabled();

    List<AiMcpToolCatalog> queryByMcpId(@Param("mcpId") String mcpId);

    int upsertBatch(@Param("list") List<AiMcpToolCatalog> list);

    int deleteByMcpIdAndToolNames(@Param("mcpId") String mcpId, @Param("toolNames") List<String> toolNames);

}
