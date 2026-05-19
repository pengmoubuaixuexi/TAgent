package cn.bugstack.ai.trigger.http;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P2.5 14.4 Rate Limiting：按 userId/sessionId 维度的请求限流。
 * <p>
 * 限流 key 优先级：X-User-Id > sessionId > remoteAddr。
 * 每个 key 独立一个 Resilience4j RateLimiter（LRU 淘汰避免内存泄漏）。
 */
@Slf4j
@Component
@Order(-100)
public class RateLimitFilter implements Filter {

    @Value("${agent.rate-limit.enabled:false}")
    private boolean enabled;

    @Value("${agent.rate-limit.limit-for-period:10}")
    private int limitForPeriod;

    @Value("${agent.rate-limit.limit-refresh-period:1s}")
    private String limitRefreshPeriod;

    @Value("${agent.rate-limit.timeout-duration:0s}")
    private String timeoutDuration;

    private RateLimiterRegistry registry;
    /** 上限避免恶意构造大量伪造 user/IP 把 map 撑爆；access-order LRU + removeEldestEntry 自动淘汰 */
    private static final int MAX_LIMITERS = 10_000;
    private final Map<String, RateLimiter> limiters = Collections.synchronizedMap(
            new LinkedHashMap<String, RateLimiter>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RateLimiter> eldest) {
                    return size() > MAX_LIMITERS;
                }
            });

    @PostConstruct
    public void initRegistry() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.parse("PT" + limitRefreshPeriod.toUpperCase()))
                .timeoutDuration(Duration.parse("PT" + timeoutDuration.toUpperCase()))
                .build();
        registry = RateLimiterRegistry.of(config);
        log.info("[RateLimit] enabled={} limitForPeriod={} refreshPeriod={} timeoutDuration={}",
                enabled, limitForPeriod, limitRefreshPeriod, timeoutDuration);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // Servlet container path; PostConstruct handles bean lifecycle path.
        if (registry == null) {
            initRegistry();
        }
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled || !(req instanceof HttpServletRequest)) {
            chain.doFilter(req, resp);
            return;
        }

        HttpServletRequest httpReq = (HttpServletRequest) req;
        String key = resolveKey(httpReq);

        RateLimiter limiter;
        synchronized (limiters) {
            limiter = limiters.computeIfAbsent(key, registry::rateLimiter);
        }
        boolean permitted = limiter.acquirePermission();

        if (!permitted) {
            log.warn("[RateLimit] denied key={} path={}", key, httpReq.getRequestURI());
            HttpServletResponse httpResp = (HttpServletResponse) resp;
            httpResp.setStatus(429);
            httpResp.setContentType("application/json;charset=UTF-8");
            httpResp.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Too many requests\"}");
            return;
        }

        chain.doFilter(req, resp);
    }

    @Override
    public void destroy() {
        limiters.clear();
    }

    private String resolveKey(HttpServletRequest req) {
        String userId = req.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) return "user:" + userId;
        String sessionId = req.getParameter("sessionId");
        if (sessionId != null && !sessionId.isBlank()) return "session:" + sessionId;
        String mdcUserId = MDC.get("userId");
        if (mdcUserId != null && !mdcUserId.isBlank()) return "mdc:" + mdcUserId;
        return "ip:" + req.getRemoteAddr();
    }
}
