package com.comassky.wallet.web;

import com.comassky.wallet.model.WalletState;
import com.comassky.wallet.model.WalletStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WalletSocketTest {
    private final Vertx vertx = Vertx.vertx();
    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<UniEmitter<? super Void>> sends = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final AtomicInteger removals = new AtomicInteger();
    private final AtomicInteger pings = new AtomicInteger();
    private final WebSocketConnection connection = (WebSocketConnection) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{WebSocketConnection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "isOpen" -> !closed.isDone();
                case "sendText" -> Uni.createFrom().<Void>emitter(emitter -> {
                    messages.add((String) arguments[0]);
                    sends.add(emitter);
                });
                case "sendPing" -> { pings.incrementAndGet(); yield Uni.createFrom().voidItem(); }
                case "close" -> { closed.complete(null); yield Uni.createFrom().voidItem(); }
                default -> throw new UnsupportedOperationException(method.getName());
            });

    @AfterEach
    void shutdown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private WalletSocket.Client client(Duration timeout) {
        WalletSocket.Client client = new WalletSocket.Client(connection, mapper, vertx, timeout);
        client.registration = removals::incrementAndGet;
        return client;
    }

    private static WalletState state(long version) {
        return new WalletState(version, WalletStatus.LIVE, null, null);
    }

    @Test
    void slowClientRetainsOnlyNewestPendingSnapshot() throws Exception {
        WalletSocket.Client client = client(Duration.ofSeconds(5));
        client.offer(state(1));
        UniEmitter<? super Void> first = sends.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals(1, mapper.readTree(messages.take()).path("version").asLong());
        client.offer(state(2));
        client.offer(state(4));
        client.offer(state(3));
        assertTrue(messages.isEmpty());
        first.complete(null);
        UniEmitter<? super Void> next = sends.poll(5, TimeUnit.SECONDS);
        assertNotNull(next);
        assertEquals(4, mapper.readTree(messages.take()).path("version").asLong());
        next.complete(null);
        client.close();
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(1, removals.get());
        client.offer(state(5));
        assertTrue(messages.isEmpty());
    }

    @Test
    void sendDeadlineClosesAndUnregistersClient() throws Exception {
        WalletSocket.Client client = client(Duration.ofMillis(50));
        client.offer(state(1));
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(1, removals.get());
        client.close();
        assertEquals(1, removals.get());
    }

    @Test
    void heartbeatExpiresOnlyAfterNinetySecondsWithoutPong() throws Exception {
        WalletSocket.Client client = client(Duration.ofSeconds(5));
        long lastPong = client.lastPong;
        client.heartbeat(lastPong + 90_000_000_000L);
        assertEquals(1, pings.get());
        assertFalse(closed.isDone());
        client.heartbeat(lastPong + 90_000_000_001L);
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(1, removals.get());
    }
}