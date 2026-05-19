package cn.bugstack.ai.trigger.http;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * P2.8 17.1 Idempotency Key：幂等请求过滤。
 * <p>
 * 客户端传 {@code Idempotency-Key} 请求头，相同 key 的重复请求返回首次缓存结果。
 * 使用 Caffeine Cache 存储，内置大小限制和过期驱逐，防止内存泄漏。
 */
@Slf4j
@Component
@Order(-90)
public class IdempotencyFilter implements Filter {

    @Value("${agent.idempotency.enabled:false}")
    private boolean enabled;

    @Value("${agent.idempotency.ttl-seconds:3600}")
    private int ttlSeconds;

    private Cache<String, CachedResponse> cache;

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled || !(req instanceof HttpServletRequest)) {
            chain.doFilter(req, resp);
            return;
        }

        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;
        String idempotencyKey = httpReq.getHeader("Idempotency-Key");

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            chain.doFilter(req, resp);
            return;
        }

        // 仅对 agent API 生效
        String path = httpReq.getRequestURI();
        if (!path.startsWith("/api/v1/agent/")) {
            chain.doFilter(req, resp);
            return;
        }

        // Claude 改进：SSE 异步端点（auto_agent / flow_agent / fixed_agent / preheat_*）
        // 返回 ResponseBodyEmitter，filter chain 立即 return，TeeWrapper 拿不到完整响应。
        // 这些路径用幂等没意义且会缓存空 body，直接放行。
        // 真正适合幂等的是 armory_*、approval、feedback 这类同步 POST。
        if (path.endsWith("/auto_agent") || path.endsWith("/flow_agent")
                || path.endsWith("/fixed_agent") || path.contains("/preheat")) {
            chain.doFilter(req, resp);
            return;
        }

        // 检查是否已有缓存响应
        CachedResponse cached = cache.getIfPresent(idempotencyKey);
        if (cached != null) {
            log.info("[Idempotency] cache hit key={}", idempotencyKey);
            httpResp.setStatus(cached.status);
            httpResp.setContentType(cached.contentType);
            httpResp.getOutputStream().write(cached.body);
            return;
        }

        // 首次请求：包装 response 捕获输出
        TeeResponseWrapper wrapper = new TeeResponseWrapper(httpResp);
        chain.doFilter(req, wrapper);
        wrapper.flushBuffer();

        // 仅缓存成功响应
        if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
            CachedResponse entry = new CachedResponse(
                    wrapper.getStatus(),
                    wrapper.getContentType(),
                    wrapper.getCapturedBytes(),
                    System.currentTimeMillis()
            );
            cache.put(idempotencyKey, entry);
            log.debug("[Idempotency] cached key={} status={} size={}", idempotencyKey, entry.status, entry.body.length);
        }
    }

    @Override
    public void destroy() {
        cache.invalidateAll();
    }

    private static class CachedResponse {
        final int status;
        final String contentType;
        final byte[] body;
        final long createdAt;

        CachedResponse(int status, String contentType, byte[] body, long createdAt) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.createdAt = createdAt;
        }
    }

    /**
     * 双写 ResponseWrapper：一份写给客户端，一份捕获到内存。
     */
    private static class TeeResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        private TeeServletOutputStream teeStream;
        private PrintWriter teeWriter;

        TeeResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (teeStream == null) {
                teeStream = new TeeServletOutputStream(super.getOutputStream(), capture);
            }
            return teeStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (teeWriter == null) {
                teeWriter = new PrintWriter(new OutputStreamWriter(
                        new TeeServletOutputStream(super.getOutputStream(), capture), StandardCharsets.UTF_8));
            }
            return teeWriter;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (teeWriter != null) teeWriter.flush();
            if (teeStream != null) teeStream.flush();
            super.flushBuffer();
        }

        byte[] getCapturedBytes() {
            return capture.toByteArray();
        }
    }

    private static class TeeServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final OutputStream capture;

        TeeServletOutputStream(ServletOutputStream delegate, OutputStream capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            capture.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            capture.write(b, off, len);
        }

        @Override
        public boolean isReady() { return delegate.isReady(); }

        @Override
        public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            capture.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            capture.close();
        }
    }
}
