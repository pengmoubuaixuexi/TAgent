package cn.bugstack.ai.infrastructure.adapter.repository;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RemoteImageDownloaderDeadlineTest {
    private HttpServer server;
    private ExecutorService executor;
    private HttpClient client;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch headersSent = new CountDownLatch(1);

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/ok", exchange -> {
            byte[] body = {1, 2, 3};
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 10);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/chunked", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[128]);
            exchange.close();
        });
        server.createContext("/declared", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 1024);
            exchange.close();
        });
        server.createContext("/html", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 100);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/ok");
            exchange.sendResponseHeaders(302, 100);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        // Warm the client so the deadline regression measures a stalled body, not initial startup.
        RemoteImageDownloader.sendBounded(client, request("/ok", 3000), 16);
    }

    @After public void stop() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private HttpRequest request(String path, long millis) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofMillis(millis)).build();
    }

    @Test public void readsCompleteImage() throws Exception {
        var response = RemoteImageDownloader.sendBounded(client, request("/ok", 1000), 16);
        assertEquals(200, response.statusCode());
        assertArrayEquals(new byte[]{1, 2, 3}, response.body());
    }

    @Test(timeout = 5000) public void deadlineCoversBodyAfterHeadersArrive() throws Exception {
        long start = System.nanoTime();
        try {
            RemoteImageDownloader.sendBounded(client, request("/stall", 400), 16);
            fail("Stalled body must time out");
        } catch (HttpTimeoutException expected) {
            assertEquals("Fixture must have sent headers and a partial body", 0, headersSent.getCount());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        }
    }

    @Test public void boundsChunkedBodyWithoutContentLength() throws Exception {
        try {
            RemoteImageDownloader.sendBounded(client, request("/chunked", 1000), 16);
            fail("Unknown-length response must still honor size limit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("大小限制"));
        }
    }

    @Test public void rejectsDeclaredOversizeBeforeReadingBody() throws Exception {
        try {
            RemoteImageDownloader.sendBounded(client, request("/declared", 1000), 16);
            fail("Oversized content-length must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("大小限制"));
        }
    }

    @Test public void rejectsNonImageBeforeReadingBody() throws Exception {
        try {
            RemoteImageDownloader.sendBounded(client, request("/html", 1000), 256);
            fail("HTML must not be imported as image");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("不是图片"));
        }
    }

    @Test(timeout = 5000) public void redirectDoesNotWaitForStalledRedirectBody() throws Exception {
        var response = RemoteImageDownloader.sendBounded(client, request("/redirect", 1500), 16);
        assertEquals(302, response.statusCode());
        assertEquals("/ok", response.headers().firstValue("Location").orElseThrow());
        assertEquals(0, response.body().length);
    }

    @Test public void interruptionCancelsBodyWait() throws Exception {
        Thread.currentThread().interrupt();
        try {
            RemoteImageDownloader.sendBounded(client, request("/stall", 2000), 16);
            fail("Interrupted caller must not block on body");
        } catch (InterruptedException expected) {
            // The public downloader restores the interrupt flag for its caller.
        } finally { Thread.interrupted(); }
    }
}
