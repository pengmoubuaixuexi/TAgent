package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理员用户表 DAO
 * @author bugstack虫洞栈
 * @description 管理员用户表数据访问对象
 */
@Mapper
public interface IAdminUserDao {

    /**
     * 插入管理员用户
     * @param adminUser 管理员用户对象
     * @return 影响行数
     */
    int insert(AdminUser adminUser);

    /**
     * 根据ID更新管理员用户
     * @param adminUser 管理员用户对象
     * @return 影响行数
     */
    int updateById(AdminUser adminUser);

    /**
     * 根据用户ID更新管理员用户
     * @param adminUser 管理员用户对象
     * @return 影响行数
     */
    int updateByUserId(AdminUser adminUser);

    /**
     * 根据ID删除管理员用户
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据用户ID删除管理员用户
     * @param userId 用户ID
     * @return 影响行数
     */
    int deleteByUserId(String userId);

    /**
     * 根据ID查询管理员用户
     * @param id 主键ID
     * @return 管理员用户对象
     */
    AdminUser queryById(Long id);

    /**
     * 根据用户ID查询管理员用户
     * @param userId 用户ID
     * @return 管理员用户对象
     */
    AdminUser queryByUserId(String userId);

    /**
     * 根据用户名查询管理员用户
     * @param username 用户名
     * @return 管理员用户对象
     */
    AdminUser queryByUsername(String username);

    /**
     * 查询启用状态的管理员用户列表
     * @return 管理员用户列表
     */
    List<AdminUser> queryEnabledUsers();

    /**
     * 根据状态查询管理员用户列表
     * @param status 状态
     * @return 管理员用户列表
     */
    List<AdminUser> queryByStatus(Integer status);

    /**
     * 查询所有管理员用户
     * @return 管理员用户列表
     */
    List<AdminUser> queryAll();

    /**
     * 用户登录验证
     * @param username 用户名
     * @param password 密码
     * @return 管理员用户对象
     */
    AdminUser queryByUsernameAndPassword(@Param("username") String username, @Param("password") String password);

    /**
     * 按条件分页查询管理员用户（条件与分页均下推到 DB，避免全量拉取内存过滤）。
     * @param userId   精确匹配用户ID，null 时忽略
     * @param username 模糊匹配用户名，null 时忽略
     * @param status   状态，null 时忽略
     * @param offset   偏移量（(pageNum-1)*pageSize）
     * @param limit    每页大小
     * @return 当前页管理员用户列表
     */
    List<AdminUser> queryPageByCondition(@Param("userId") String userId,
                                         @Param("username") String username,
                                         @Param("status") Integer status,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    /**
     * 按同一组条件统计总数（用于分页 total）。
     */
    long countByCondition(@Param("userId") String userId,
                          @Param("username") String username,
                          @Param("status") Integer status);

}