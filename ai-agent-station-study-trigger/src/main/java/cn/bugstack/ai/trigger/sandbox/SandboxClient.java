package cn.bugstack.ai.trigger.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP client of the sandbox runner (internal network only, shared token). */
@Component
public class SandboxClient {

    public record Input(String name, Path file) { }

    public static final class SandboxUnavailable extends IOException {
        public SandboxUnavailable(String message) { super(message); }
        public SandboxUnavailable(String message, Throwable cause) { super(message, cause); }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String token;

    public SandboxClient(@Value("${agent.sandbox.url:}") String baseUrl, @Value("${agent.sandbox.token:}") String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    public boolean configured() {
        return !baseUrl.isBlank() && token.length() >= 24;
    }

    /** Submits one execution; returns the runner's snapshot (executionId, state, position). */
    public JsonNode submit(Map<String, Object> meta, String code, List<Input> inputs) throws IOException, InterruptedException {
        String boundary = "----tagent" + UUID.randomUUID().toString().replace("-", "");
        List<Object> parts = new ArrayList<>();
        parts.add(field(boundary, "meta", json.writeValueAsString(meta)));
        parts.add(field(boundary, "code", code == null ? "" : code));
        for (Input input : inputs) {
            String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"inputs\"; filename=\""
                    + input.name().replace("\"", "_").replace("\r", "_").replace("\n", "_")
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            parts.add(header.getBytes(StandardCharsets.UTF_8));
            parts.add(input.file());
            parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/executions"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> stream(parts)))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 413) throw new IOException("附件总量超过沙箱允许的大小");
        if (response.statusCode() != 202) throw new SandboxUnavailable("sandbox submit failed: HTTP " + response.statusCode());
        return json.readTree(response.body());
    }

    public JsonNode status(String executionId) throws IOException, InterruptedException {
        HttpResponse<String> response = send(get("/v1/executions/" + executionId, 15));
        if (response.statusCode() == 404) throw new SandboxUnavailable("execution is unknown to the sandbox");
        if (response.statusCode() != 200) throw new SandboxUnavailable("sandbox status failed: HTTP " + response.statusCode());
        return json.readTree(response.body());
    }

    public byte[] output(String executionId, int index, long maxBytes) throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = http.send(get("/v1/executions/" + executionId + "/outputs/" + index, 60), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new SandboxUnavailable("sandbox is unreachable", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new SandboxUnavailable("sandbox output failed: HTTP " + response.statusCode());
            byte[] bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) throw new IOException("output is larger than allowed");
            return bytes;
        }
    }

    public void deleteRun(String run) throws IOException, InterruptedException {
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/runs/" + run)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token).DELETE().build());
    }

    private HttpRequest get(String path, int seconds) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(seconds))
                .header("Authorization", "Bearer " + token).GET().build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (e instanceof SandboxUnavailable) throw e;
            throw new SandboxUnavailable("sandbox is unreachable", e);
        }
    }

    private static byte[] field(String boundary, String name, String value) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A fresh stream of all parts; file contents are read lazily so large inputs are not held in memory. */
    private static InputStream stream(List<Object> parts) {
        List<InputStream> streams = new ArrayList<>(parts.size());
        for (Object part : parts) {
            if (part instanceof byte[] bytes) {
                streams.add(new ByteArrayInputStream(bytes));
            } else {
                Path file = (Path) part;
                streams.add(new InputStream() {
                    private InputStream delegate;

                    private InputStream delegate() throws IOException {
                        if (delegate == null) delegate = Files.newInputStream(file);
                        return delegate;
                    }

                    @Override public int read() throws IOException { return delegate().read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return delegate().read(b, off, len); }
                    @Override public void close() throws IOException { if (delegate != null) delegate.close(); }
                });
            }
        }
        return new SequenceInputStream(Collections.enumeration(streams));
    }
}
