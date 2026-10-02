package cn.bugstack.ai.trigger.security;

import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AccountService {
    private final IAdminUserDao users;
    private final PasswordEncoder passwords;

    public AccountService(IAdminUserDao users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public AdminUser register(String username, String password) {
        username = username == null ? "" : username.trim();
        if (!username.matches("[A-Za-z0-9_]{3,32}") || password == null
                || password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "用户名需为 3–32 位字母、数字或下划线；密码至少 10 位且不超过 72 字节");
        }
        if ("admin".equalsIgnoreCase(username) || users.queryByUsername(username) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户名不可用");
        }
        AdminUser user = AdminUser.builder().userId(UUID.randomUUID().toString()).username(username)
                .password(passwords.encode(password)).role("USER").status(1)
                .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
        try {
            users.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户名不可用");
        }
        return user;
    }

    public AdminUser authenticate(String username, String password) {
        AdminUser user = username == null ? null : users.queryByUsername(username.trim());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || password == null
                || password.getBytes(StandardCharsets.UTF_8).length > 72 || user.getPassword() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String stored = user.getPassword();
        boolean encoded = stored.matches("^\\$2[aby]\\$.*");
        boolean valid = encoded ? passwords.matches(password, stored)
                : MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        // Preserve existing accounts and transparently upgrade their legacy plaintext passwords.
        if (!encoded) {
            user.setPassword(passwords.encode(password));
            user.setUpdateTime(LocalDateTime.now());
            users.updateById(user);
        }
        return user;
    }
}
