package com.example.walletviewer;

import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ElectrumClientTest {
    private Vertx vertx;
    private ElectrumClient client;
    private NetServer server;
    private final CopyOnWriteArrayList<Integer> ids = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NetSocket> sockets = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<NetSocket, JsonObject> respond;
    private volatile Consumer<NetSocket> accepted = ignored -> { };

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        respond = (socket, request) -> socket.write(new JsonObject()
                .put("id", request.getInteger("id")).put("result", "ok").encode() + "\n");
        server = newServer();
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        client = new ElectrumClient();
        client.vertx = vertx;
        client.host = "127.0.0.1";
        client.port = server.actualPort();
        client.requestTimeout = Duration.ofSeconds(2);
        client.init();
    }

    private NetServer newServer() {
        return vertx.createNetServer().connectHandler(socket -> {
            sockets.add(socket);
            socket.handler(RecordParser.newDelimited("\n", buffer -> {
                JsonObject request = new JsonObject(buffer);
                ids.add(request.getInteger("id"));
                respond.accept(socket, request);
            }));
            accepted.accept(socket);
        });
    }

    @AfterEach
    void stop() throws Exception {
        if (client != null) client.close();
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void eachSubscriptionUsesAUniqueIdAndReleasesPendingRequests() throws Exception {
        Uni<JsonObject> call = client.call("server.ping");
        assertTrue(ids.isEmpty());
        assertEquals("ok", call.await().atMost(Duration.ofSeconds(5)).getString("result"));
        assertEquals("ok", call.await().atMost(Duration.ofSeconds(5)).getString("result"));
        assertEquals(2, ids.size());
        assertNotEquals(ids.get(0), ids.get(1));
        assertTrue(pending().isEmpty());
    }

    @Test
    void silentServerTimesOutAndReleasesPendingRequests() throws Exception {
        respond = (socket, request) -> { };
        client.requestTimeout = Duration.ofMillis(250);
        assertThrows(TimeoutException.class,
                () -> client.call("server.ping").await().atMost(Duration.ofSeconds(5)));
        assertTrue(pending().isEmpty());
    }

    @Test
    void cancellationReleasesPendingRequests() throws Exception {
        CompletableFuture<Void> received = new CompletableFuture<>();
        respond = (socket, request) -> received.complete(null);
        Cancellable subscription = client.call("server.ping").subscribe().with(
                ignored -> fail("Unexpected response"), ignored -> fail("Unexpected failure"));
        received.get(5, TimeUnit.SECONDS);
        assertEquals(1, pending().size());
        subscription.cancel();
        assertTrue(pending().isEmpty());
    }

    @Test
        void reusedRequestReconnectsAfterServerDisconnect() throws Exception {
        respond = (socket, request) -> socket.close();
        Uni<JsonObject> call = client.call("server.ping");
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> call.await().atMost(Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("connection closed"));
        assertTrue(pending().isEmpty());

        respond = (socket, request) -> socket.write(new JsonObject()
            .put("id", request.getInteger("id")).put("result", "reconnected").encode() + "\n");
        assertEquals("reconnected", call.await().atMost(Duration.ofSeconds(5)).getString("result"));
        assertNotEquals(ids.get(0), ids.get(1));
        }

        @Test
    void malformedMessagesDoNotPreventFollowingValidResponses() {
        respond = (socket, request) -> socket.write("not-json\n{\"id\":\"unexpected\"}\n"
                + new JsonObject().put("id", request.getInteger("id")).put("result", "ok").encode() + "\n");
        assertEquals("ok", client.call("server.ping").await().atMost(Duration.ofSeconds(5)).getString("result"));
    }

    @Test
    void rpcErrorsReleasePendingRequests() throws Exception {
        respond = (socket, request) -> socket.write(new JsonObject().put("id", request.getInteger("id"))
                .put("error", new JsonObject().put("code", -1).put("message", "test error")).encode() + "\n");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client.call("server.ping").await().atMost(Duration.ofSeconds(5)));
        assertTrue(failure.getMessage().contains("Electrum error"));
        assertTrue(pending().isEmpty());
    }

    @Test
    void notificationsValidateShapeIsolateCallbacksAndAllowRemoval() throws Exception {
        JsonObject script = new JsonObject().put("method", "blockchain.scripthash.subscribe")
                .put("params", new JsonArray().add("ab".repeat(32)).add("status"));
        JsonObject emptyScript = script.copy().put("params", new JsonArray().add("ab".repeat(32)).addNull());
        JsonObject header = new JsonObject().put("method", "blockchain.headers.subscribe")
                .put("params", new JsonArray().add(new JsonObject().put("height", 123).put("hex", "00")));
        List<JsonObject> malformed = List.of(
                script.copy().put("id", "1"),
                script.copy().putNull("id"),
                script.copy().put("method", "unknown.subscribe"),
                script.copy().put("params", "not-an-array"),
                script.copy().put("params", new JsonArray().add(123).add("status")),
                script.copy().put("params", new JsonArray().add("hash").add(123)),
                script.copy().put("params", new JsonArray().add("hash")),
                script.copy().put("params", new JsonArray().add("hash").addNull().add("extra")),
                header.copy().put("params", new JsonArray().add("not-a-header")),
                header.copy().put("params", new JsonArray()),
                header.copy().put("result", "not-a-notification"));
        respond = (socket, request) -> {
            StringBuilder messages = new StringBuilder("not-json\n[]\n");
            malformed.forEach(message -> messages.append(message.encode()).append('\n'));
            messages.append(script.encode()).append('\n')
                    .append(emptyScript.encode()).append('\n')
                    .append(header.encode()).append('\n')
                    // Even a response carrying a recognized method must remain an RPC ack.
                    .append(script.copy().put("id", request.getInteger("id")).put("result", "ok").encode())
                    .append('\n');
            socket.write(messages.toString());
        };
        client.onNotification(message -> {
            message.clear();
            throw new IllegalStateException("listener failed");
        });
        CopyOnWriteArrayList<JsonObject> delivered = new CopyOnWriteArrayList<>();
        AutoCloseable registration = client.onNotification(delivered::add);
        AtomicInteger retained = new AtomicInteger();
        client.onNotification(ignored -> retained.incrementAndGet());

        assertEquals("ok", client.call("blockchain.scripthash.subscribe", "ab".repeat(32))
                .await().atMost(Duration.ofSeconds(5)).getString("result"));
        assertEquals(List.of(script, emptyScript, header), delivered);
        assertEquals(3, retained.get());
        registration.close();
        registration.close();
        client.call("blockchain.scripthash.subscribe", "ab".repeat(32)).await().atMost(Duration.ofSeconds(5));
        assertEquals(3, delivered.size());
        assertEquals(6, retained.get());
        assertTrue(pending().isEmpty());
    }

    @Test
    void monitoringConnectsWithoutCallsAndReconnectsWithStateCallbacks() throws Exception {
        client.reconnectInitialDelayMillis = 25;
        client.reconnectMaxDelayMillis = 100;
        CompletableFuture<NetSocket> firstSocket = new CompletableFuture<>();
        accepted = firstSocket::complete;
        CopyOnWriteArrayList<Boolean> states = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> firstReady = new CompletableFuture<>();
        CompletableFuture<Void> secondReady = new CompletableFuture<>();
        AtomicInteger readyCount = new AtomicInteger();
        client.onConnectionChange(ignored -> { throw new IllegalStateException("listener failed"); });
        client.onConnectionChange(state -> {
            states.add(state);
            if (state && readyCount.incrementAndGet() == 1) firstReady.complete(null);
            else if (state) secondReady.complete(null);
        });
        CopyOnWriteArrayList<Boolean> removableStates = new CopyOnWriteArrayList<>();
        AutoCloseable removable = client.onConnectionChange(removableStates::add);

        client.startMonitoring();
        client.startMonitoring();
        firstReady.get(5, TimeUnit.SECONDS);
        // Taking the transport monitor also waits for the entire ready callback dispatch.
        synchronized (client) {
            assertEquals(List.of(true), removableStates);
            removable.close();
        }
        assertTrue(ids.isEmpty(), "Monitoring must connect before any RPC is sent");
        firstSocket.get(5, TimeUnit.SECONDS).close();
        secondReady.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(true, false, true), states);
        assertEquals(List.of(true), removableStates);
        assertEquals("ok", client.call("server.ping").await().atMost(Duration.ofSeconds(5)).getString("result"));
        assertEquals(2, sockets.size());
    }

    @Test
    void monitoringRetriesInitialConnectionFailureAndDeduplicatesDisconnectedState() throws Exception {
        server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        client.reconnectInitialDelayMillis = 25;
        client.reconnectMaxDelayMillis = 50;
        CopyOnWriteArrayList<Boolean> states = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        client.onConnectionChange(state -> {
            states.add(state);
            if (state) ready.complete(null);
            else failed.complete(null);
        });
        client.startMonitoring();
        failed.get(5, TimeUnit.SECONDS);
        server = newServer();
        server.listen(client.port, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        ready.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(false, true), states);
        assertTrue(ids.isEmpty());
    }

    @Test
    void heartbeatDetectsSilentSocketFailsPendingAndReconnects() throws Exception {
        client.heartbeatIntervalMillis = 25;
        client.reconnectInitialDelayMillis = 25;
        client.reconnectMaxDelayMillis = 50;
        client.requestTimeout = Duration.ofMillis(500);
        CompletableFuture<Void> ping = new CompletableFuture<>();
        CompletableFuture<Void> applicationRequest = new CompletableFuture<>();
        respond = (socket, request) -> {
            if ("server.ping".equals(request.getString("method"))) ping.complete(null);
            else applicationRequest.complete(null);
        };
        CopyOnWriteArrayList<Boolean> states = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> reconnected = new CompletableFuture<>();
        AtomicInteger readyCount = new AtomicInteger();
        client.onConnectionChange(state -> {
            states.add(state);
            if (state && readyCount.incrementAndGet() == 2) reconnected.complete(null);
        });
        client.startMonitoring();
        ping.get(5, TimeUnit.SECONDS);
        // This request must be failed by the heartbeat's timeout, not its own timeout.
        client.requestTimeout = Duration.ofSeconds(10);
        client.heartbeatIntervalMillis = 30_000;
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        client.call("blockchain.headers.subscribe").subscribe().with(
                ignored -> failure.completeExceptionally(new AssertionError("Unexpected response")), failure::complete);
        applicationRequest.get(5, TimeUnit.SECONDS);
        assertInstanceOf(TimeoutException.class, failure.get(5, TimeUnit.SECONDS));
        respond = (socket, request) -> socket.write(new JsonObject()
                .put("id", request.getInteger("id")).putNull("result").encode() + "\n");
        reconnected.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(true, false, true), states);
        assertNull(client.call("server.ping").await().atMost(Duration.ofSeconds(5)).getValue("result"));
        assertTrue(pending().isEmpty());
    }

    @Test
    void monitoredApplicationTimeoutClosesSocketAndReconnects() throws Exception {
        client.requestTimeout = Duration.ofMillis(250);
        client.reconnectInitialDelayMillis = 25;
        client.reconnectMaxDelayMillis = 50;
        respond = (socket, request) -> { };
        CompletableFuture<Void> reconnected = new CompletableFuture<>();
        AtomicInteger readyCount = new AtomicInteger();
        client.onConnectionChange(state -> {
            if (state && readyCount.incrementAndGet() == 2) reconnected.complete(null);
        });
        client.startMonitoring();
        assertThrows(TimeoutException.class,
                () -> client.call("blockchain.headers.subscribe").await().atMost(Duration.ofSeconds(5)));
        reconnected.get(5, TimeUnit.SECONDS);
        respond = (socket, request) -> socket.write(new JsonObject()
            .put("id", request.getInteger("id")).put("result", "ok").encode() + "\n");
        client.call("server.ping").await().atMost(Duration.ofSeconds(5));
        assertEquals(2, sockets.size());
        assertTrue(pending().isEmpty());
    }

    @Test
    void concurrentLazyCallsAndMonitoringShareOneConnection() throws Exception {
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<?>[] calls = new CompletableFuture<?>[12];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = CompletableFuture.runAsync(() -> {
                release.join();
                assertEquals("ok", client.call("server.ping")
                        .await().atMost(Duration.ofSeconds(5)).getString("result"));
            });
        }
        release.complete(null);
        client.startMonitoring();
        CompletableFuture.allOf(calls).get(10, TimeUnit.SECONDS);
        assertEquals(1, sockets.size());
        assertEquals(12, ids.stream().distinct().count());
        assertTrue(pending().isEmpty());
    }

    @Test
    void shutdownFailsPendingCancelsMonitoringAndCannotReconnect() throws Exception {
        client.heartbeatIntervalMillis = 25;
        client.reconnectInitialDelayMillis = 25;
        client.reconnectMaxDelayMillis = 50;
        CompletableFuture<Void> received = new CompletableFuture<>();
        respond = (socket, request) -> received.complete(null);
        CopyOnWriteArrayList<Boolean> states = new CopyOnWriteArrayList<>();
        client.onConnectionChange(states::add);
        client.startMonitoring();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        client.call("blockchain.headers.subscribe").subscribe().with(
                ignored -> failure.completeExceptionally(new AssertionError("Unexpected response")), failure::complete);
        received.get(5, TimeUnit.SECONDS);
        client.close();
        assertInstanceOf(IllegalStateException.class, failure.get(5, TimeUnit.SECONDS));
        assertTrue(pending().isEmpty());
        client.startMonitoring();
        assertThrows(IllegalStateException.class,
                () -> client.call("server.ping").await().atMost(Duration.ofSeconds(5)));
        // Let obsolete socket callbacks and any uncancelled short timers run.
        CompletableFuture<Void> settled = new CompletableFuture<>();
        vertx.setTimer(200, ignored -> settled.complete(null));
        settled.get(5, TimeUnit.SECONDS);
        assertEquals(1, sockets.size());
        assertEquals(List.of(true, false), states);
    }

    private Map<?, ?> pending() throws Exception {
        Field field = ElectrumClient.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(client);
    }
}