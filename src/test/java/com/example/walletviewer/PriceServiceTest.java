package com.example.walletviewer;

import com.sun.net.httpserver.HttpServer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PriceServiceTest {
    private HttpServer server;
    private PriceService service;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        body = quote(Instant.now().getEpochSecond());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prices", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        service = new PriceService();
        service.pricesUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/prices");
        service.init();
    }

    @AfterEach
    void stop() {
        if (service != null) service.close();
        if (server != null) server.stop(0);
    }

    @Test
    void loadsLazilyAndSharesCachedQuotes() {
        Uni<PriceRatesDto> first = service.rates();
        assertEquals(0, requests.get());
        var result = Uni.combine().all().unis(first, service.rates()).asTuple()
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(60_000, result.getItem1().eur());
        assertEquals(70_000, result.getItem1().usd());
        assertSame(result.getItem1(), result.getItem2());
        assertSame(result.getItem1(), service.rates().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void providerFailureReturns503AndIsBrieflyCached() {
        status = 429;
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> service.rates().await().atMost(Duration.ofSeconds(5)));
        assertEquals(503, error.getResponse().getStatus());
        assertThrows(ServiceUnavailableException.class,
                () -> service.rates().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void malformedResponseReturns503() {
        body = "not-json";
        assertThrows(ServiceUnavailableException.class,
                () -> service.rates().await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsMissingZeroNegativeAndOutdatedQuotes() {
        long now = Instant.now().getEpochSecond();
        assertThrows(IllegalArgumentException.class, () -> PriceService.parse("{}"));
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.parse(new JsonObject(quote(now)).put("EUR", 0).encode()));
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.parse(new JsonObject(quote(now)).put("USD", -1).encode()));
        assertThrows(IllegalArgumentException.class, () -> PriceService.parse(quote(now - 3600)));
        assertThrows(IllegalArgumentException.class, () -> PriceService.parse(quote(now + 3600)));
    }

    private static String quote(long time) {
        return new JsonObject().put("EUR", 60_000).put("USD", 70_000).put("time", time).encode();
    }
}