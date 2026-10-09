package cn.bugstack.ai.trigger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.lang.reflect.Type;

@ControllerAdvice
public class AuthenticatedBodyAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper json;
    private final HttpServletRequest request;
    private final ConversationAccess access;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.bugstack.ai.domain.agent.adapter.repository.IWorkspaceAccessRepository workspaceAccess;
    public AuthenticatedBodyAdvice(ObjectMapper json, HttpServletRequest request, ConversationAccess access) {
        this.json = json; this.request = request; this.access = access;
    }
    @Override public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/agent/");
    }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter p, Type t,
            Class<? extends HttpMessageConverter<?>> c) throws IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getPrincipal())) return input;
        var tree = json.readTree(input.getBody());
        if (!(tree instanceof ObjectNode body)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String userId = auth.getName();
        body.put("userId", userId);
        // This public deployment has one tenant; clients cannot select another tenant.
        body.remove("tenantId");
        String path = request.getRequestURI();
        String agentId = body.path("aiAgentId").asText(null);
        if (path.endsWith("/auto_agent") && agentId != null && !agentId.isBlank()
                && workspaceAccess != null && !workspaceAccess.ownsAgent(userId, agentId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent 不存在、未启用或无权使用");
        boolean create = path.endsWith("/auto_agent") || path.endsWith("/background-tasks/interpret");
        if (path.endsWith("/auto_agent") && body.path("sessionId").asText("").isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少会话标识");
        access.session(body.path("sessionId").asText(null), userId, create);
        access.session(body.path("conversationId").asText(null), userId, false);
        access.run(body.path("runId").asText(null), userId, path.endsWith("/auto_agent"));
        access.run(body.path("sourceRunId").asText(null), userId);
        // Approval/input IDs embed the generating session; tool names may contain colons.
        for (String key : new String[]{"approvalId", "inputId"}) {
            String id = body.path(key).asText(null);
            if (id != null) {
                int separator = id.indexOf(':');
                if (separator < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                access.session(id.substring(0, separator), userId, false);
            }
        }
        byte[] bytes = json.writeValueAsBytes(body);
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders(); headers.putAll(input.getHeaders());
                headers.setContentLength(bytes.length); return headers;
            }
        };
    }
}
