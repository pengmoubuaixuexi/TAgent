package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiEventLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 步骤事件日志 DAO（P2.8 17.2）。
 */
@Mapper
public interface IAiEventLogDao {

    void insert(AiEventLog po);
}
