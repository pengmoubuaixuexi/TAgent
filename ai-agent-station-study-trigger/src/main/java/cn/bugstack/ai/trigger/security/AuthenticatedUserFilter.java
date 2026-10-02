package cn.bugstack.ai.trigger.security;

import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;

/** Runs after Spring Security (-100); headers and query identities are server-owned. */
@Component
@Order(-90)
public class AuthenticatedUserFilter extends OncePerRequestFilter {
    private final IAdminUserDao users;
    private final ConversationAccess access;
    public AuthenticatedUserFilter(IAdminUserDao users, ConversationAccess access) {
        this.users = users; this.access = access;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            chain.doFilter(request, response); return;
        }
        var account = users.queryByUserId(auth.getName());
        boolean wasAdmin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())
                || wasAdmin != "ADMIN".equals(account.getRole())) {
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            response.sendError(401); return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        try {
            if (path.startsWith("/api/v1/agent/")) {
                access.session(request.getParameter("sessionId"), auth.getName(), false);
                access.session(request.getParameter("conversationId"), auth.getName(), false);
                var match = java.util.regex.Pattern.compile("^/api/v1/agent/(?:runs|run-snapshots)/([^/]+)(?:/.*)?$").matcher(path);
                if (match.matches()) access.run(match.group(1), auth.getName());
            }
        } catch (ResponseStatusException e) { response.sendError(e.getStatusCode().value()); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                if ("X-Tenant-Id".equalsIgnoreCase(name)) return null;
                return "X-User-Id".equalsIgnoreCase(name) ? auth.getName() : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                if ("X-Tenant-Id".equalsIgnoreCase(name)) return Collections.emptyEnumeration();
                return "X-User-Id".equalsIgnoreCase(name) ? Collections.enumeration(List.of(auth.getName())) : super.getHeaders(name);
            }
            @Override public String getParameter(String name) {
                return "userId".equals(name) ? auth.getName() : super.getParameter(name);
            }
            @Override public String[] getParameterValues(String name) {
                return "userId".equals(name) ? new String[]{auth.getName()} : super.getParameterValues(name);
            }
            @Override public Map<String, String[]> getParameterMap() {
                var params = new HashMap<>(super.getParameterMap());
                params.put("userId", new String[]{auth.getName()});
                return Collections.unmodifiableMap(params);
            }
        }, response);
    }
}
