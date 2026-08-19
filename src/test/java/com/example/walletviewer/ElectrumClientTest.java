package com.example.walletviewer;

import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ElectrumClientTest {
    private Vertx vertx;
    private ElectrumClient client;
    private final CopyOnWriteArrayList<Integer> ids = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<NetSocket, JsonObject> respond;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        respond = (socket, request) -> socket.write(new JsonObject()
                .put("id", request.getInteger("id")).put("result", "ok").encode() + "\n");
        NetServer server = vertx.createNetServer().connectHandler(socket ->
                socket.handler(RecordParser.newDelimited("\n", buffer -> {
                    JsonObject request = new JsonObject(buffer);
                    ids.add(request.getInteger("id"));
                    respond.accept(socket, request);
                })));
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        client = new ElectrumClient();
        client.vertx = vertx;
        client.host = "127.0.0.1";
        client.port = server.actualPort();
        client.requestTimeout = Duration.ofSeconds(2);
        client.init();
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

    private Map<?, ?> pending() throws Exception {
        Field field = ElectrumClient.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(client);
    }
}