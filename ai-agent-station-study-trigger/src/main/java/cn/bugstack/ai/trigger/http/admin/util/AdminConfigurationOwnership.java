package cn.bugstack.ai.trigger.http.admin.util;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Legacy admin editors create private resources owned by the authenticated administrator. */
@Component
public class AdminConfigurationOwnership {
    private static final Map<String, String[]> TABLES = Map.of(
            "agent", new String[]{"ai_agent", "agent_id"},
            "client", new String[]{"ai_client", "client_id"},
            "model", new String[]{"ai_client_model", "model_id"},
            "api", new String[]{"ai_client_api", "api_id"},
            "prompt", new String[]{"ai_client_system_prompt", "prompt_id"},
            "advisor", new String[]{"ai_client_advisor", "advisor_id"},
            "tool_mcp", new String[]{"ai_client_tool_mcp", "mcp_id"});
    private final JdbcTemplate jdbc;

    public AdminConfigurationOwnership(@org.springframework.beans.factory.annotation.Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String currentOwner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return authentication.getName();
    }

    public void requireOwned(String owner, String type, String id) {
        String[] resource = TABLES.get(type);
        if (resource == null || id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置关系包含无效资源");
        }
        // Identifiers come only from the fixed allowlist above. Values remain bound parameters.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + resource[0]
                + " WHERE " + resource[1] + "=? AND owner_user_id=?", Integer.class, id, owner);
        if (!Integer.valueOf(1).equals(count)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能装配本人拥有的配置资源");
        }
    }
}
