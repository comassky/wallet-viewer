package com.comassky.wallet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(value = LiveWalletTestResource.class, restrictToAnnotatedClass = true)
class LiveWalletIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/api/wallet/live")
    URI liveHttpUri;

    @TestHTTPResource("/api/wallet")
    URI walletUri;

    // Populated by QuarkusTestResourceLifecycleManager.TestInjector, not CDI.
    LiveWalletTestResource electrum;

    @Test
    void realSocketReceivesCreditReplaysCacheAndRejectsForeignOrigins() throws Exception {
        assertNotNull(electrum);
        assertEquals("localhost", liveHttpUri.getHost());
        assertEquals("/api/wallet/live", liveHttpUri.getPath());
        String origin = liveHttpUri.getScheme() + "://" + liveHttpUri.getRawAuthority();
        URI liveUri = new URI("https".equals(liveHttpUri.getScheme()) ? "wss" : "ws",
                liveHttpUri.getRawAuthority(), liveHttpUri.getPath(), null, null);

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY).build()) {
            JsonNode credited;
            int creditedScans;
            int creditedHistories;
            try (SocketSession first = SocketSession.open(client, liveUri, origin)) {
                // Startup can replay loading/syncing or an already completed live snapshot.
                JsonNode initial = first.listener.await(state -> isLiveWithTotal(state, 0));
                JsonNode empty = initial.path("snapshot");
                assertTrue(initial.path("version").asLong() > 0);
                assertEquals(0, empty.path("balance").path("confirmed").asLong());
                assertEquals(0, empty.path("balance").path("unconfirmed").asLong());
                assertEquals(0, empty.path("utxos").size());
                assertEquals(0, empty.path("transactions").size());
                assertEquals(0, empty.path("receiveAddress").path("index").asInt());
                assertEquals(LiveWalletTestResource.ADDRESS, empty.path("receiveAddress").path("address").asText());
                assertEquals(0, electrum.notificationCount(), "Subscribe acknowledgements must not push changes");
                electrum.assertHealthy();

                int initialScans = electrum.scanCount();
                int initialHistories = electrum.historyCount();
                assertTrue(initialScans > 0);
                assertTrue(initialHistories >= 4, "Both receive and change gaps must be scanned");
                assertRestSnapshot(client, empty);
                assertRestServer(client);
                first.refresh();
                assertEquals(initial, first.listener.await(state -> true), "Refresh must replay the initial cache");
                assertEquals(initialScans, electrum.scanCount());
                assertEquals(initialHistories, electrum.historyCount());

                electrum.credit();
                credited = first.listener.await(state -> isLiveWithTotal(state, LiveWalletTestResource.VALUE));
                assertTrue(credited.path("version").asLong() > initial.path("version").asLong());
                assertCredit(credited.path("snapshot"));
                assertEquals(1, electrum.notificationCount());
                creditedScans = electrum.scanCount();
                creditedHistories = electrum.historyCount();
                assertTrue(creditedScans > initialScans, "Electrum notification must trigger a scan");
                assertTrue(creditedHistories > initialHistories);
                assertRestSnapshot(client, credited.path("snapshot"));
                assertEquals(creditedScans, electrum.scanCount(), "REST must use the completed cache");
                assertEquals(creditedHistories, electrum.historyCount());
            }

            // This is a new WebSocket after a completed close handshake, not the old listener.
            try (SocketSession reconnect = SocketSession.open(client, liveUri, origin)) {
                assertEquals(credited, reconnect.listener.await(state -> true),
                        "The very first reconnect frame must replay the latest version and snapshot");
                reconnect.refresh();
                assertEquals(credited, reconnect.listener.await(state -> true),
                        "The literal refresh message must replay the current state");
                assertEquals(creditedScans, electrum.scanCount(), "Reconnect/refresh must not scan");
                assertEquals(creditedHistories, electrum.historyCount());
            }

            // The request still targets localhost: only its Origin header is foreign.
            assertHandshakeRejected(client, liveUri, "https://evil.example");
            assertHandshakeRejected(client, liveUri, null);
            assertEquals(creditedScans, electrum.scanCount());
            assertEquals(creditedHistories, electrum.historyCount());
            electrum.assertHealthy();
        }
    }

    private static boolean isLiveWithTotal(JsonNode state, long total) {
        JsonNode balance = state.path("snapshot").path("balance").path("total");
        return "live".equals(state.path("status").asText()) && balance.isIntegralNumber() && balance.asLong() == total;
    }

    @Test
    void negativeReceiveIndicesAreRejectedWithBadRequest() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY).build()) {
            for (String path : new String[]{"/receive/-1", "/receive/-1/qr"}) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create(walletUri.toString() + path)).timeout(TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode(), response.body());
            }
        }
    }

    private void assertRestSnapshot(HttpClient client, JsonNode expected) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(walletUri).timeout(TIMEOUT)
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(expected, JSON.readTree(response.body()));
    }

    private void assertRestServer(HttpClient client) throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            URI.create(walletUri.toString() + "/server")).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(JSON.createObjectNode().put("host", "127.0.0.1").put("port", electrum.port())
                    .put("tls", false).put("connected", true).put("serverVersion", "fixture 1.0")
                    .put("protocolVersion", "1.4"), JSON.readTree(response.body()));
        }
        assertEquals(1, electrum.versionCount(), "REST reads must never renegotiate metadata");
    }

    private void assertCredit(JsonNode snapshot) {
        assertEquals(0, snapshot.path("balance").path("confirmed").asLong());
        assertEquals(LiveWalletTestResource.VALUE, snapshot.path("balance").path("unconfirmed").asLong());
        assertEquals(LiveWalletTestResource.VALUE, snapshot.path("balance").path("total").asLong());
        assertEquals(1, snapshot.path("utxos").size());
        JsonNode utxo = snapshot.path("utxos").get(0);
        assertEquals(electrum.transactionId(), utxo.path("txid").asText());
        assertEquals(LiveWalletTestResource.ADDRESS, utxo.path("address").asText());
        assertEquals(LiveWalletTestResource.VALUE, utxo.path("value").asLong());
        assertEquals(0, utxo.path("height").asInt());
        assertEquals(0, utxo.path("confirmations").asInt());
        assertEquals(1, snapshot.path("transactions").size());
        JsonNode transaction = snapshot.path("transactions").get(0);
        assertEquals(electrum.transactionId(), transaction.path("txid").asText());
        assertEquals(LiveWalletTestResource.VALUE, transaction.path("amount").asLong());
        assertEquals(LiveWalletTestResource.VALUE, transaction.path("received").asLong());
        assertEquals(0, transaction.path("sent").asLong());
        assertEquals(0, transaction.path("height").asInt());
        assertEquals(0, transaction.path("confirmations").asInt());
        assertEquals("received", transaction.path("type").asText());
        assertEquals(1, snapshot.path("receiveAddress").path("index").asInt());
    }

    private static void assertHandshakeRejected(HttpClient client, URI uri, String origin) {
        WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(TIMEOUT);
        if (origin != null) builder.header("Origin", origin);
        CompletableFuture<WebSocket> attempt = builder.buildAsync(uri, new StateListener());
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> attempt.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Origin must be rejected: " + origin);
            WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
            int status = handshake.getResponse().statusCode();
            // Configurator SecurityException mapping is container-specific (not necessarily 403).
            assertTrue(status >= 400 && status < 600, "Expected HTTP handshake rejection, got " + status);
        } finally {
            attempt.thenAccept(WebSocket::abort); // Also clean up an unexpectedly accepted socket.
            attempt.cancel(true);
        }
    }

    private static final class SocketSession implements AutoCloseable {
        private final WebSocket socket;
        private final StateListener listener;

        private SocketSession(WebSocket socket, StateListener listener) {
            this.socket = socket;
            this.listener = listener;
        }

        static SocketSession open(HttpClient client, URI uri, String origin) throws Exception {
            StateListener listener = new StateListener();
            CompletableFuture<WebSocket> opening = client.newWebSocketBuilder().connectTimeout(TIMEOUT)
                    .header("Origin", origin).buildAsync(uri, listener);
            try {
                return new SocketSession(opening.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), listener);
            } catch (Exception failure) {
                opening.thenAccept(WebSocket::abort);
                opening.cancel(true);
                throw failure;
            }
        }

        void refresh() throws Exception {
            socket.sendText("refresh", true).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
                assertEquals(WebSocket.NORMAL_CLOSURE, listener.closed.get(5, TimeUnit.SECONDS).intValue());
            } finally {
                socket.abort();
            }
        }
    }

    private static final class StateListener implements WebSocket.Listener {
        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket socket) { socket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            try {
                fragments.append(data);
                if (last) {
                    JsonNode state = JSON.readTree(fragments.toString());
                    if (state == null || !state.isObject()) throw new IOException("Expected a wallet state object");
                    events.add(state);
                    fragments.setLength(0);
                }
            } catch (Exception failure) {
                fragments.setLength(0);
                events.add(failure);
            } finally {
                socket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            events.add(new IOException("Unexpected binary wallet frame"));
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            // java.net.http automatically sends the matching pong; continue receiving frames.
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            events.add(new IOException("WebSocket closed: " + statusCode + " " + reason));
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable failure) {
            events.add(failure);
            closed.completeExceptionally(failure);
        }

        JsonNode await(Predicate<JsonNode> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            JsonNode last = null;
            while (true) {
                long remaining = deadline - System.nanoTime();
                Object event = remaining > 0 ? events.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (event == null) throw new AssertionError("Timed out waiting for wallet state; last frame: " + last);
                if (event instanceof Throwable failure) throw new AssertionError("WebSocket failed", failure);
                last = (JsonNode) event;
                if (predicate.test(last)) return last;
            }
        }
    }
}