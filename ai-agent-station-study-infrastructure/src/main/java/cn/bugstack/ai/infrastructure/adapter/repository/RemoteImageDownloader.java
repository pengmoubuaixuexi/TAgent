package cn.bugstack.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Component
class RemoteImageDownloader {

    private static final int MAX_REDIRECTS = 3;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    DownloadedImage download(String value, long maxBytes) {
        URI current = requirePublicHttpUri(value);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "image/*")
                    .header("User-Agent", "TAgent-Image-Importer/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw new IllegalArgumentException("下载 URL 图片失败: " + safeMessage(e), e);
            }

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IllegalArgumentException("图片重定向缺少 Location"));
                current = requirePublicHttpUri(current.resolve(location).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new IllegalArgumentException("下载 URL 图片失败，HTTP " + status);
            }

            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) {
                closeQuietly(response.body());
                throw new IllegalArgumentException("URL 图片超过大小限制");
            }
            String mimeType = response.headers().firstValue("content-type")
                    .map(RemoteImageDownloader::normalizeMimeType)
                    .orElse("");
            if (!mimeType.startsWith("image/")) {
                closeQuietly(response.body());
                throw new IllegalArgumentException("URL 返回的不是图片: " + mimeType);
            }
            byte[] data = readLimited(response.body(), maxBytes);
            if (data.length == 0) throw new IllegalArgumentException("URL 图片内容为空");
            return new DownloadedImage(current.toString(), mimeType, data);
        }
        throw new IllegalArgumentException("图片重定向次数超过 " + MAX_REDIRECTS);
    }

    private static byte[] readLimited(InputStream input, long maxBytes) {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) throw new IllegalArgumentException("URL 图片超过大小限制");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取 URL 图片失败: " + safeMessage(e), e);
        }
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

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    record DownloadedImage(String finalUrl, String mimeType, byte[] data) {
    }
}
