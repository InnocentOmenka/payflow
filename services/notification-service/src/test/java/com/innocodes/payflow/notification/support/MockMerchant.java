package com.innocodes.payflow.notification.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class MockMerchant implements AutoCloseable {
    public static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public final List<Request> received = new CopyOnWriteArrayList<>();
    public volatile int status = 204;
    public volatile long delayMillis;

    public MockMerchant() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Request(body, Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestMethod()));
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                if (status == 302) exchange.getResponseHeaders().add("Location", "/redirect-target");
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirect-target", exchange -> {
            received.add(new Request("REDIRECTED", Map.of(), exchange.getRequestMethod()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
    }

    public void reset() {
        received.clear();
        status = 204;
        delayMillis = 0;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public record Request(String body, Map<String, List<String>> headers, String method) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().orElseThrow().getValue().getFirst();
        }
    }
}
