package com.comassky.wallet.web;

import com.comassky.wallet.model.WalletState;
import com.comassky.wallet.service.WalletLiveService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.nio.ByteBuffer;

/** One endpoint instance per browser. Slow clients retain at most one pending full state. */
@ServerEndpoint(value = "/api/wallet/live", configurator = WalletSocketOrigin.class)
public class WalletSocket {
    @Inject WalletLiveService wallets;
    @Inject ObjectMapper mapper;
    @Inject Vertx vertx;
    private Session session;
    private AutoCloseable registration;
    private WalletState pending;
    private boolean sending;
    private boolean closed;
    private long timer = -1;
    private volatile long lastPong;

    @OnOpen
    public void open(Session session) {
        this.session = session;
        session.setMaxTextMessageBufferSize(64);
        session.getAsyncRemote().setSendTimeout(10_000);
        lastPong = System.nanoTime();
        // Never take the socket lock while holding the cache/coordinator lock.
        registration = wallets.listen(state -> vertx.runOnContext(ignored -> send(state)));
        timer = vertx.setPeriodic(30_000, ignored -> {
            if (System.nanoTime() - lastPong > 90_000_000_000L) { close(); return; }
            try { session.getAsyncRemote().sendPing(ByteBuffer.wrap(new byte[]{1})); }
            catch (IOException | RuntimeException failure) { close(); }
        });
    }

    @OnMessage
    public void message(String message) {
        if ("refresh".equals(message)) send(wallets.current());
    }

    @OnMessage
    public void pong(PongMessage message) { lastPong = System.nanoTime(); }

    private synchronized void send(WalletState state) {
        if (closed || !session.isOpen()) return;
        if (sending) {
            if (pending == null || pending.version() < state.version()) pending = state;
            return;
        }
        sending = true;
        try {
            session.getAsyncRemote().sendText(mapper.writeValueAsString(state), result -> {
                synchronized (this) {
                    sending = false;
                    if (!result.isOK()) { close(); return; }
                    WalletState next = pending;
                    pending = null;
                    if (next != null) send(next);
                }
            });
        } catch (JsonProcessingException | RuntimeException failure) { close(); }
    }

    @OnError
    public void error(Throwable failure) { close(); }

    @OnClose
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (timer != -1) vertx.cancelTimer(timer);
        if (registration != null) try { registration.close(); } catch (Exception ignored) { }
        pending = null;
        if (session != null && session.isOpen()) try { session.close(); } catch (IOException ignored) { }
    }
}