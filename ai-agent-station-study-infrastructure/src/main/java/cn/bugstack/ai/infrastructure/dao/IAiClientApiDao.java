package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AiClientApi;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI客户端API配置表 DAO
 * @author bugstack虫洞栈
 * @description AI客户端API配置表数据访问对象
 */
@Mapper
public interface IAiClientApiDao {

    /**
     * 插入AI客户端API配置
     * @param aiClientApi AI客户端API配置对象
     * @return 影响行数
     */
    int insert(AiClientApi aiClientApi);

    /**
     * 根据ID更新AI客户端API配置
     * @param aiClientApi AI客户端API配置对象
     * @return 影响行数
     */
    int updateById(AiClientApi aiClientApi);

    /**
     * 根据API ID更新AI客户端API配置
     * @param aiClientApi AI客户端API配置对象
     * @return 影响行数
     */
    int updateByApiId(AiClientApi aiClientApi);

    /**
     * 根据ID删除AI客户端API配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据API ID删除AI客户端API配置
     * @param apiId API ID
     * @return 影响行数
     */
    int deleteByApiId(String apiId);

    /**
     * 根据ID查询AI客户端API配置
     * @param id 主键ID
     * @return AI客户端API配置对象
     */
    AiClientApi queryById(Long id);

    /**
     * 根据API ID查询AI客户端API配置
     * @param apiId API ID
     * @return AI客户端API配置对象
     */
    AiClientApi queryByApiId(String apiId);

    /**
     * 查询所有启用的AI客户端API配置
     * @return AI客户端API配置列表
     */
    List<AiClientApi> queryEnabledApis();

    /**
     * 查询所有AI客户端API配置
     * @return AI客户端API配置列表
     */
    List<AiClientApi> queryAll();

}