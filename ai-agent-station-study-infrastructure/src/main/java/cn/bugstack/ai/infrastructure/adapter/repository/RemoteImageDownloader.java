package cn.bugstack.ai.infrastructure.adapter.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
class RemoteImageDownloader {

    private static final int MAX_REDIRECTS = 3;
    private final HttpClient client;

    RemoteImageDownloader() {
        this("", System.getenv());
    }

    RemoteImageDownloader(Map<String, String> environment) {
        this("", environment);
    }

    @Autowired
    RemoteImageDownloader(
            @Value("${agent.multimodal.remote-image-proxy-url:}") String configuredProxyUrl) {
        this(configuredProxyUrl, System.getenv());
    }

    RemoteImageDownloader(String configuredProxyUrl, Map<String, String> environment) {
        this.client = buildClient(configuredProxyUrl, environment);
    }

    static HttpClient buildClient(Map<String, String> environment) {
        return buildClient("", environment);
    }

    static HttpClient buildClient(String configuredProxyUrl, Map<String, String> environment) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector proxySelector = proxySelector(configuredProxyUrl, environment);
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        return builder.build();
    }

    private static ProxySelector proxySelector(
            String configuredProxyUrl,
            Map<String, String> environment) {
        String value = configuredProxyUrl == null || configuredProxyUrl.isBlank()
                ? firstNonBlank(
                        environment,
                        "HTTPS_PROXY", "https_proxy",
                        "HTTP_PROXY", "http_proxy")
                : configuredProxyUrl;
        if (value == null) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            }
            return ProxySelector.of(new InetSocketAddress(uri.getHost(), port));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(Map<String, String> environment, String... names) {
        if (environment == null) return null;
        for (String name : names) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    DownloadedImage download(String value, long maxBytes) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        URI current = requirePublicHttpUri(value);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalArgumentException("下载 URL 图片超时（含响应体）");
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofNanos(remaining))
                    .header("Accept", "image/*")
                    .header("User-Agent", "TAgent-Image-Importer/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response;
            try {
                response = sendBounded(client, request, maxBytes);
            } catch (HttpTimeoutException e) {
                throw new IllegalArgumentException("下载 URL 图片超时（含响应体）", e);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalArgumentException("下载 URL 图片失败: " + safeMessage(e), e);
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IllegalArgumentException("图片重定向缺少 Location"));
                current = requirePublicHttpUri(current.resolve(location).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException("下载 URL 图片失败，HTTP " + status);
            }

            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) {
                throw new IllegalArgumentException("URL 图片超过大小限制");
            }
            String mimeType = response.headers().firstValue("content-type")
                    .map(RemoteImageDownloader::normalizeMimeType)
                    .orElse("");
            if (!mimeType.startsWith("image/")) {
                throw new IllegalArgumentException("URL 返回的不是图片: " + mimeType);
            }
            byte[] data = response.body();
            if (data.length == 0) throw new IllegalArgumentException("URL 图片内容为空");
            return new DownloadedImage(current.toString(), mimeType, data);
        }
        throw new IllegalArgumentException("图片重定向次数超过 " + MAX_REDIRECTS);
    }

    // The request timeout alone does not bound reads from BodyHandlers.ofInputStream().
    // Await a bounded subscriber's complete body and cancel the exchange at the deadline.
    static HttpResponse<byte[]> sendBounded(HttpClient client, HttpRequest request, long maxBytes)
            throws IOException, InterruptedException {
        var future = client.sendAsync(request, info -> {
            boolean discard = info.statusCode() < 200 || info.statusCode() >= 300;
            if (!discard) {
                long declared = info.headers().firstValueAsLong("content-length").orElse(-1);
                if (declared > maxBytes) throw new IllegalArgumentException("URL 图片超过大小限制");
                String type = normalizeMimeType(info.headers().firstValue("content-type").orElse(""));
                if (!type.startsWith("image/")) throw new IllegalArgumentException("URL 返回的不是图片: " + type);
            }
            return new LimitedImageBody(maxBytes, discard);
        });
        try {
            return future.get(request.timeout().orElse(Duration.ofSeconds(30)).toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("URL image response body exceeded deadline");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof IllegalArgumentException invalid) throw invalid;
            throw new IOException("URL image exchange failed", cause);
        }
    }

    private static final class LimitedImageBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long maxBytes;
        private final boolean discard;
        private Flow.Subscription subscription;

        private LimitedImageBody(long maxBytes, boolean discard) {
            this.maxBytes = maxBytes;
            this.discard = discard;
        }

        public CompletionStage<byte[]> getBody() { return result; }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (discard) {
                subscription.cancel();
                result.complete(new byte[0]);
            } else subscription.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("URL 图片超过大小限制"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private static URI requirePublicHttpUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("图片 URL 仅支持 http/https");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivate(address)) {
                    throw new IllegalArgumentException("图片 URL 不允许访问本机或内网地址");
                }
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("图片 URL 无法解析", e);
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String normalizeMimeType(String value) {
        int separator = value.indexOf(';');
        return (separator >= 0 ? value.substring(0, separator) : value)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    record DownloadedImage(String finalUrl, String mimeType, byte[] data) {
    }
}
