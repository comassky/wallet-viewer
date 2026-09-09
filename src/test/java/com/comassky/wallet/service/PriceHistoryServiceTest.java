package com.comassky.wallet.service;

import com.comassky.wallet.model.PricePointDto;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryServiceTest {
    private HttpServer server;
    private Vertx vertx;
    private MempoolFetch fetch;
    private PriceHistoryService service;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        body = history();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/history", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        vertx = Vertx.vertx();
        fetch = new MempoolFetch(vertx);
        service = new PriceHistoryService();
        service.fetch = fetch;
        service.historyUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/history");
        service.init();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (vertx != null) vertx.closeAndAwait();
    }

    @Test
    void loadsSortsAscendingAndSharesCache() {
        var result = Uni.combine().all().unis(service.history(), service.history()).asTuple()
                .await().atMost(Duration.ofSeconds(5));
        List<PricePointDto> points = result.getItem1();
        assertEquals(2, points.size());
        assertEquals(1_000, points.get(0).time());
        assertEquals(2_000, points.get(1).time());
        assertEquals(60_000, points.get(0).eur());
        assertEquals(70_000, points.get(0).usd());
        assertSame(result.getItem1(), result.getItem2());
        assertEquals(1, requests.get());
    }

    @Test
    void providerFailureReturns503() {
        status = 500;
        assertThrows(ServiceUnavailableException.class,
                () -> service.history().await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsEmptyOrInvalidHistory() {
        assertThrows(IllegalArgumentException.class,
                () -> PriceHistoryService.parse(new JsonObject().put("prices", new JsonArray()).encode()));
        assertThrows(IllegalArgumentException.class,
                () -> PriceHistoryService.parse(new JsonObject().encode()));
        assertThrows(IllegalArgumentException.class, () -> PriceHistoryService.parse(
                new JsonObject().put("prices", new JsonArray().add(new JsonObject().put("time", 1).put("EUR", 0).put("USD", 0))).encode()));
    }

    private static String history() {
        // Unsorted on purpose: the service must sort ascending by time.
        JsonArray prices = new JsonArray()
                .add(new JsonObject().put("time", 2_000).put("EUR", 61_000).put("USD", 71_000))
                .add(new JsonObject().put("time", 1_000).put("EUR", 60_000).put("USD", 70_000));
        return new JsonObject().put("prices", prices).encode();
    }
}
