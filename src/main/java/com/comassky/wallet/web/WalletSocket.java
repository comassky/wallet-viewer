package com.comassky.wallet.web;

import com.comassky.wallet.model.WalletState;
import com.comassky.wallet.service.WalletLiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnPongMessage;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.NonBlocking;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;

import java.time.Duration;

@WebSocket(path = "/api/wallet/live")
public class WalletSocket {
    private static final UserData.TypedKey<Client> CLIENT = new UserData.TypedKey<>("wallet-client");
    @Inject WalletLiveService wallets;
    @Inject ObjectMapper mapper;
    @Inject Vertx vertx;

    @OnOpen
    @NonBlocking
    public void open(WebSocketConnection connection) {
        Client client = new Client(connection, mapper, vertx);
        connection.userData().put(CLIENT, client);
        client.registration = wallets.listen(client::offer);
        client.timer = vertx.setPeriodic(30_000, ignored -> client.heartbeat(System.nanoTime()));
    }

    @OnTextMessage
    @NonBlocking
    public void message(String message, WebSocketConnection connection) {
        Client client = connection.userData().get(CLIENT);
        if (client != null && "refresh".equals(message)) client.offer(wallets.current());
    }

    @OnPongMessage
    @NonBlocking
    public void pong(Buffer message, WebSocketConnection connection) {
        Client client = connection.userData().get(CLIENT);
        if (client != null) client.lastPong = System.nanoTime();
    }

    @OnError
    @NonBlocking
    public void error(Throwable failure, WebSocketConnection connection) { close(connection); }

    @OnClose
    @NonBlocking
    public void close(WebSocketConnection connection) {
        Client client = connection.userData().get(CLIENT);
        if (client != null) client.close();
    }

    static final class Client {
        private final WebSocketConnection connection;
        private final ObjectMapper mapper;
        private final Vertx vertx;
        private final Duration sendTimeout;
        AutoCloseable registration;
        long timer = -1;
        volatile long lastPong = System.nanoTime();
        private WalletState pending;
        private boolean sending;
        private boolean closed;

        Client(WebSocketConnection connection, ObjectMapper mapper, Vertx vertx) {
            this(connection, mapper, vertx, Duration.ofSeconds(10));
        }

        Client(WebSocketConnection connection, ObjectMapper mapper, Vertx vertx, Duration sendTimeout) {
            this.connection = connection;
            this.mapper = mapper;
            this.vertx = vertx;
            this.sendTimeout = sendTimeout;
        }

        synchronized void offer(WalletState state) {
            if (closed || !connection.isOpen()) return;
            if (pending == null || pending.version() < state.version()) pending = state;
            if (sending) return;
            sending = true;
            vertx.runOnContext(ignored -> drain());
        }

        private synchronized void drain() {
            if (closed) return;
            WalletState state = pending;
            pending = null;
            try {
                connection.sendText(mapper.writeValueAsString(state))
                        .ifNoItem().after(sendTimeout).fail()
                        .subscribe().with(ignored -> sent(), failure -> close());
            } catch (Exception failure) { close(); }
        }

        private synchronized void sent() {
            if (closed) return;
            if (pending == null) sending = false;
            else vertx.runOnContext(ignored -> drain());
        }

        synchronized void heartbeat(long now) {
            if (closed) return;
            if (now - lastPong > 90_000_000_000L) { close(); return; }
            connection.sendPing(Buffer.buffer(new byte[]{1}))
                    .ifNoItem().after(sendTimeout).fail()
                    .subscribe().with(ignored -> {}, failure -> close());
        }

        void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                pending = null;
            }
            vertx.runOnContext(ignored -> {
                if (timer != -1) vertx.cancelTimer(timer);
                if (registration != null) try { registration.close(); } catch (Exception failure) { }
                if (connection.isOpen()) connection.close().subscribe().with(nothing -> {}, failure -> {});
            });
        }
    }
}