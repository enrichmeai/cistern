package com.enrichmeai.cistern.auth;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on a loopback port that serves the captured documents, so the
 * {@link CachingJwksClient} is tested over the wire it will actually use — WebClient, HTTP,
 * JSON bodies — rather than against a stub of itself. Counts requests per path so tests can
 * assert on caching and rate limiting.
 */
final class FixtureServer implements AutoCloseable {

    private static final int STATUS_OK = 200;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int BACKLOG = 0;
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    FixtureServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), BACKLOG);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            String body = bodies.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(STATUS_NOT_FOUND, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(CONTENT_TYPE, APPLICATION_JSON);
            exchange.sendResponseHeaders(STATUS_OK, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** Serve {@code body} at {@code path}; replaces whatever was there. */
    FixtureServer serve(String path, String body) {
        bodies.put(path, body);
        return this;
    }

    /** Stop serving {@code path}: subsequent requests 404. */
    FixtureServer remove(String path) {
        bodies.remove(path);
        return this;
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
