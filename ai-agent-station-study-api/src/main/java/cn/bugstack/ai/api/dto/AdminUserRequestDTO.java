package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理员用户请求 DTO
 *
 * @author bugstack虫洞栈
 * @description 管理员用户请求数据传输对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdminUserRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（更新时使用）
     */
    private Long id;

    /**
     * 用户ID（唯一标识）
     */
    private String userId;

    /**
     * 用户名（登录账号）
     */
    private String username;

    /**
     * 密码（加密存储）
     */
    private String password;

    /**
     * 状态(0:禁用,1:启用,2:锁定)
     */
    private Integer status;

}