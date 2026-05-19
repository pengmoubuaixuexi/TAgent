package cn.bugstack.ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级追踪字段注入 Filter。
 * <p>
 * P1.6 后职责拆分：
 * - {@code requestId}（UUID，本 filter 写入）：业务侧自带的请求关联键，X-Trace-Id 响应头沿用历史约定，
 *   ELK / observe.html 旧查询继续可用；
 * - {@code traceId} / {@code spanId}（W3C 16/8 字节 hex，Micrometer Tracing 自动写入）：OTel 标准 trace 上下文，
 *   Jaeger UI 用它串 4 step 跨度。
 * <p>
 * P1.2.3 多租户隔离：新增 {@code userId} / {@code tenantId} 两个 MDC 键，
 * 从 HTTP header（{@code X-User-Id} / {@code X-Tenant-Id}）解析；下游 WM/LTM/日志按需使用。
 * <p>
 * 两键并存，logback whitelist 都暴露到 ES，按需检索。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class MdcTraceFilter extends OncePerRequestFilter {

    /** 业务请求关联 ID（UUID-no-dash）。OTel traceId 由 Micrometer Tracing 自动写到 MDC，不要混用。 */
    public static final String REQUEST_ID = "requestId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** P1.2.3 多租户隔离：用户 / 租户 ID 从 header 读取，写入 MDC 全链路透传 */
    public static final String USER_ID = "userId";
    public static final String TENANT_ID = "tenantId";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_TRACE_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(REQUEST_ID, requestId);
        response.setHeader(HEADER_TRACE_ID, requestId);

        // P1.2.3：userId / tenantId 写入 MDC，异步线程由 ThreadPoolConfig 的 ContextSnapshot 接力
        String userId = request.getHeader(HEADER_USER_ID);
        if (userId != null && !userId.isBlank()) {
            MDC.put(USER_ID, userId);
        }
        String tenantId = request.getHeader(HEADER_TENANT_ID);
        if (tenantId != null && !tenantId.isBlank()) {
            MDC.put(TENANT_ID, tenantId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
            MDC.remove(USER_ID);
            MDC.remove(TENANT_ID);
        }
    }
}
