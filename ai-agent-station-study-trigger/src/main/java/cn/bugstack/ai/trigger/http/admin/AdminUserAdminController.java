package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAdminUserAdminService;
import cn.bugstack.ai.api.dto.AdminUserLoginRequestDTO;
import cn.bugstack.ai.api.dto.AdminUserQueryRequestDTO;
import cn.bugstack.ai.api.dto.AdminUserRequestDTO;
import cn.bugstack.ai.api.dto.AdminUserResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 管理员用户管理控制器
 *
 * @author bugstack虫洞栈
 * @description 管理员用户管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/admin-user")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AdminUserAdminController implements IAdminUserAdminService {

    @Resource
    private IAdminUserDao adminUserDao;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAdminUser(@RequestBody AdminUserRequestDTO request) {
        try {
            log.info("创建管理员用户请求：{}", request);
            
            // DTO转PO
            AdminUser adminUser = convertToAdminUser(request);
            adminUser.setCreateTime(LocalDateTime.now());
            adminUser.setUpdateTime(LocalDateTime.now());
            
            int result = adminUserDao.insert(adminUser);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("创建管理员用户失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAdminUserById(@RequestBody AdminUserRequestDTO request) {
        try {
            log.info("根据ID更新管理员用户请求：{}", request);
            
            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AdminUser adminUser = convertToAdminUser(request);
            adminUser.setUpdateTime(LocalDateTime.now());
            
            int result = adminUserDao.updateById(adminUser);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新管理员用户失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-user-id")
    public Response<Boolean> updateAdminUserByUserId(@RequestBody AdminUserRequestDTO request) {
        try {
            log.info("根据用户ID更新管理员用户请求：{}", request);
            
            if (!StringUtils.hasText(request.getUserId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("用户ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AdminUser adminUser = convertToAdminUser(request);
            adminUser.setUpdateTime(LocalDateTime.now());
            
            int result = adminUserDao.updateByUserId(adminUser);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据用户ID更新管理员用户失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAdminUserById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除管理员用户请求：{}", id);
            
            int result = adminUserDao.deleteById(id);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除管理员用户失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-user-id/{userId}")
    public Response<Boolean> deleteAdminUserByUserId(@PathVariable("userId") String userId) {
        try {
            log.info("根据用户ID删除管理员用户请求：{}", userId);
            
            int result = adminUserDao.deleteByUserId(userId);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据用户ID删除管理员用户失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AdminUserResponseDTO> queryAdminUserById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询管理员用户请求：{}", id);
            
            AdminUser adminUser = adminUserDao.queryById(id);
            if (adminUser == null) {
                return Response.<AdminUserResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AdminUserResponseDTO responseDTO = convertToAdminUserResponseDTO(adminUser);
            
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询管理员用户失败", e);
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-user-id/{userId}")
    public Response<AdminUserResponseDTO> queryAdminUserByUserId(@PathVariable("userId") String userId) {
        try {
            log.info("根据用户ID查询管理员用户请求：{}", userId);
            
            AdminUser adminUser = adminUserDao.queryByUserId(userId);
            if (adminUser == null) {
                return Response.<AdminUserResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AdminUserResponseDTO responseDTO = convertToAdminUserResponseDTO(adminUser);
            
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据用户ID查询管理员用户失败", e);
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-username/{username}")
    public Response<AdminUserResponseDTO> queryAdminUserByUsername(@PathVariable("username") String username) {
        try {
            log.info("根据用户名查询管理员用户请求：{}", username);
            
            AdminUser adminUser = adminUserDao.queryByUsername(username);
            if (adminUser == null) {
                return Response.<AdminUserResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(null)
                        .build();
            }
            
            AdminUserResponseDTO responseDTO = convertToAdminUserResponseDTO(adminUser);
            
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据用户名查询管理员用户失败", e);
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AdminUserResponseDTO>> queryEnabledAdminUsers() {
        try {
            log.info("查询启用状态的管理员用户列表");
            
            List<AdminUser> adminUsers = adminUserDao.queryEnabledUsers();
            List<AdminUserResponseDTO> responseDTOs = adminUsers.stream()
                    .map(this::convertToAdminUserResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询启用状态的管理员用户列表失败", e);
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-status/{status}")
    public Response<List<AdminUserResponseDTO>> queryAdminUsersByStatus(@PathVariable("status") Integer status) {
        try {
            log.info("根据状态查询管理员用户列表请求：{}", status);
            
            List<AdminUser> adminUsers = adminUserDao.queryByStatus(status);
            List<AdminUserResponseDTO> responseDTOs = adminUsers.stream()
                    .map(this::convertToAdminUserResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据状态查询管理员用户列表失败", e);
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AdminUserResponseDTO>> queryAdminUserList(@RequestBody AdminUserQueryRequestDTO request) {
        try {
            log.info("根据条件查询管理员用户列表请求：{}", request);
            
            // 这里可以根据查询条件进行过滤，暂时先查询所有
            List<AdminUser> adminUsers = adminUserDao.queryAll();
            
            // 根据查询条件进行过滤
            List<AdminUser> filteredUsers = adminUsers.stream()
                    .filter(user -> {
                        boolean match = true;
                        if (StringUtils.hasText(request.getUserId())) {
                            match = match && user.getUserId().equals(request.getUserId());
                        }
                        if (StringUtils.hasText(request.getUsername())) {
                            match = match && user.getUsername().contains(request.getUsername());
                        }
                        if (request.getStatus() != null) {
                            match = match && user.getStatus().equals(request.getStatus());
                        }
                        return match;
                    })
                    .collect(Collectors.toList());
            
            // 分页处理
            int pageNum = request.getPageNum() != null ? request.getPageNum() : 1;
            int pageSize = request.getPageSize() != null ? request.getPageSize() : 10;
            int startIndex = (pageNum - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, filteredUsers.size());
            
            List<AdminUser> pagedUsers = filteredUsers.subList(startIndex, endIndex);
            List<AdminUserResponseDTO> responseDTOs = pagedUsers.stream()
                    .map(this::convertToAdminUserResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据条件查询管理员用户列表失败", e);
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AdminUserResponseDTO>> queryAllAdminUsers() {
        try {
            log.info("查询所有管理员用户");
            
            List<AdminUser> adminUsers = adminUserDao.queryAll();
            List<AdminUserResponseDTO> responseDTOs = adminUsers.stream()
                    .map(this::convertToAdminUserResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有管理员用户失败", e);
            return Response.<List<AdminUserResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/login")
    public Response<AdminUserResponseDTO> loginAdminUser(@RequestBody AdminUserLoginRequestDTO request) {
        try {
            log.info("管理员用户登录请求：{}", request.getUsername());
            
            AdminUser adminUser = adminUserDao.queryByUsernameAndPassword(request.getUsername(), request.getPassword());
            if (adminUser == null) {
                return Response.<AdminUserResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("用户名或密码错误")
                        .data(null)
                        .build();
            }
            
            // 检查用户状态
            if (adminUser.getStatus() == 0) {
                return Response.<AdminUserResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("用户已被禁用")
                        .data(null)
                        .build();
            }
            
            if (adminUser.getStatus() == 2) {
                return Response.<AdminUserResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("用户已被锁定")
                        .data(null)
                        .build();
            }
            
            AdminUserResponseDTO responseDTO = convertToAdminUserResponseDTO(adminUser);
            
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("管理员用户登录失败", e);
            return Response.<AdminUserResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/validate-login")
    public Response<Boolean> validateAdminUserLogin(@RequestBody AdminUserLoginRequestDTO request) {
        try {
            log.info("管理员用户登录校验请求：{}", request.getUsername());
            
            // 参数校验
            if (!StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("用户名或密码不能为空")
                        .data(false)
                        .build();
            }
            
            // 查询用户
            AdminUser adminUser = adminUserDao.queryByUsernameAndPassword(request.getUsername(), request.getPassword());
            if (adminUser == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.LOGIN_FAILED.getCode())
                        .info(ResponseCode.LOGIN_FAILED.getInfo())
                        .data(false)
                        .build();
            }
            
            // 检查用户状态
            if (adminUser.getStatus() == 0) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.LOGIN_FAILED.getCode())
                        .info("用户已被禁用")
                        .data(false)
                        .build();
            }
            
            if (adminUser.getStatus() == 2) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.LOGIN_FAILED.getCode())
                        .info("用户已被锁定")
                        .data(false)
                        .build();
            }
            
            // 登录校验成功
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("管理员用户登录校验失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    /**
     * DTO转PO
     */
    private AdminUser convertToAdminUser(AdminUserRequestDTO requestDTO) {
        AdminUser adminUser = new AdminUser();
        BeanUtils.copyProperties(requestDTO, adminUser);
        return adminUser;
    }

    /**
     * PO转DTO
     */
    private AdminUserResponseDTO convertToAdminUserResponseDTO(AdminUser adminUser) {
        AdminUserResponseDTO responseDTO = new AdminUserResponseDTO();
        BeanUtils.copyProperties(adminUser, responseDTO);
        return responseDTO;
    }

}