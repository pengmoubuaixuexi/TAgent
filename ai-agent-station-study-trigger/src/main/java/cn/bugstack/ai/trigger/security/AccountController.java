package cn.bugstack.ai.trigger.security;

import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.AdminUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = "application/json")
public class AccountController {
    private final AccountService accounts;
    private final IAdminUserDao users;
    private final SecurityContextRepository contexts;
    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterWrite(Duration.ofMinutes(10)).build();
    @Value("${agent.auth.registration-enabled:true}") private boolean registrationEnabled;

    public AccountController(AccountService accounts, IAdminUserDao users, SecurityContextRepository contexts) {
        this.accounts = accounts; this.users = users; this.contexts = contexts;
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Credentials(String username, String password) {}

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
    @PostMapping("/register") public Map<String, Object> register(@RequestBody Credentials input,
            HttpServletRequest request) {
        throttle("register:" + request.getRemoteAddr(), 10);
        if (!registrationEnabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "注册暂未开放");
        return result(accounts.register(input.username(), input.password()));
    }
    @PostMapping("/login") public Map<String, Object> login(@RequestBody Credentials input,
            HttpServletRequest request, HttpServletResponse response) {
        throttle("login:" + request.getRemoteAddr(), 60);
        throttle("account:" + (input.username() == null ? "" : input.username().trim().toLowerCase(java.util.Locale.ROOT)), 20);
        AdminUser user = accounts.authenticate(input.username(), input.password());
        if (request.getSession(false) != null) request.changeSessionId();
        var session = request.getSession();
        session.setAttribute("userId", user.getUserId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute("role", user.getRole());
        session.setAttribute("isAdmin", "ADMIN".equals(user.getRole()));
        var auth = UsernamePasswordAuthenticationToken.authenticated(user.getUserId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + ("ADMIN".equals(user.getRole()) ? "ADMIN" : "USER"))));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return result(user);
    }
    @GetMapping("/me") public Map<String, Object> me(Authentication authentication) {
        AdminUser user = users.queryByUserId(authentication.getName());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账户不可用");
        return result(user);
    }
    @GetMapping("/admin-check") public void adminCheck(Authentication authentication, HttpServletResponse response) {
        if (authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        response.setStatus(204);
    }
    private void throttle(String key, int limit) {
        if (attempts.get(key, k -> new AtomicInteger()).incrementAndGet() > limit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "尝试过于频繁，请稍后再试");
    }
    private static Map<String, Object> result(AdminUser user) {
        return Map.of("code", "0000", "info", "成功", "data", Map.of(
                "userId", user.getUserId(), "username", user.getUsername(),
                "role", "ADMIN".equals(user.getRole()) ? "ADMIN" : "USER"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> error(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode())
                .body(Map.of("code", String.valueOf(e.getStatusCode().value()), "info",
                        e.getReason() == null ? "请求失败" : e.getReason()));
    }
}
