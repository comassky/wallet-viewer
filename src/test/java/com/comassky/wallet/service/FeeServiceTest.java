package com.comassky.wallet.service;

import com.comassky.wallet.model.FeeRatesDto;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FeeServiceTest {
    private HttpServer server;
    private FeeService service;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body;

    @BeforeEach
    void start() throws Exception {
        body = recommended();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fees", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        service = new FeeService();
        service.feesUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/fees");
        service.init();
    }

    @AfterEach
    void stop() {
        if (service != null) service.close();
        if (server != null) server.stop(0);
    }

    @Test
    void loadsLazilyAndSharesCachedEstimates() {
        Uni<FeeRatesDto> first = service.fees();
        assertEquals(0, requests.get());
        var result = Uni.combine().all().unis(first, service.fees()).asTuple()
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(20, result.getItem1().fastest());
        assertEquals(12, result.getItem1().halfHour());
        assertEquals(8, result.getItem1().hour());
        assertEquals(3, result.getItem1().economy());
        assertEquals(1, result.getItem1().minimum());
        assertTrue(result.getItem1().timestamp() > 0);
        assertSame(result.getItem1(), result.getItem2());
        assertSame(result.getItem1(), service.fees().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void providerFailureReturns503AndIsBrieflyCached() {
        status = 429;
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> service.fees().await().atMost(Duration.ofSeconds(5)));
        assertEquals(503, error.getResponse().getStatus());
        assertThrows(ServiceUnavailableException.class,
                () -> service.fees().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void malformedResponseReturns503() {
        body = "not-json";
        assertThrows(ServiceUnavailableException.class,
                () -> service.fees().await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsMissingZeroAndNegativeEstimates() {
        assertThrows(IllegalArgumentException.class, () -> FeeService.parse("{}"));
        assertThrows(IllegalArgumentException.class,
                () -> FeeService.parse(new JsonObject(recommended()).put("fastestFee", 0).encode()));
        assertThrows(IllegalArgumentException.class,
                () -> FeeService.parse(new JsonObject(recommended()).put("hourFee", -1).encode()));
    }

    private static String recommended() {
        return new JsonObject()
                .put("fastestFee", 20).put("halfHourFee", 12).put("hourFee", 8)
                .put("economyFee", 3).put("minimumFee", 1).encode();
    }
}
