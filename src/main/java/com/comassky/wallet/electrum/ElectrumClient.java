package com.comassky.wallet.electrum;

import com.comassky.wallet.model.ElectrumServerDto;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Reactive (Vert.x) client for the ElectrumX / electrs JSON-RPC protocol.
 * Uses a single persistent TCP connection; requests are matched to responses by id.
 */
@ApplicationScoped
public class ElectrumClient {

    private static final Logger LOG = Logger.getLogger(ElectrumClient.class);

    @ConfigProperty(name = "electrum.host", defaultValue = "127.0.0.1")
    String host = "127.0.0.1";
    @ConfigProperty(name = "electrum.port", defaultValue = "50001")
    int port = 50001;
    @ConfigProperty(name = "electrum.ssl", defaultValue = "false")
    boolean ssl;
    @ConfigProperty(name = "electrum.request-timeout", defaultValue = "30s")
    Duration requestTimeout = Duration.ofSeconds(30);

    @Inject
    Vertx vertx;

    // Package-visible timing overrides for local transport tests; no extra configuration required.
    long heartbeatIntervalMillis = 30_000;
    long reconnectInitialDelayMillis = 1_000;
    long reconnectMaxDelayMillis = 30_000;

    private NetClient client;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final CopyOnWriteArrayList<Consumer<JsonObject>> notificationListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();

    // Lifecycle state is guarded by this monitor. A Connection also identifies a connect attempt,
    // so late callbacks from an obsolete socket cannot fail requests on its replacement.
    private Connection connection;
    private boolean monitoring;
    private boolean stopped;
    private Boolean connected;
    private long reconnectTimer = -1;
    private long heartbeatTimer = -1;
    private long nextReconnectDelay;
    private Cancellable heartbeatRequest;

    private static final class Connection {
        final CompletableFuture<NetSocket> ready = new CompletableFuture<>();
        NetSocket socket;
        boolean connecting = true;
        boolean retired;
        String serverVersion;
        String protocolVersion;
    }

    private record Pending(Connection connection, UniEmitter<? super JsonObject> emitter) { }

    @PostConstruct
    void init() {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("electrum.request-timeout must be positive");
        }
        NetClientOptions opts = new NetClientOptions()
                .setConnectTimeout(10_000)
            .setReconnectAttempts(0);
        if (ssl) {
            opts.setSsl(true).setHostnameVerificationAlgorithm("HTTPS");
        }
        client = vertx.createNetClient(opts);
    }

    @PreDestroy
    synchronized void close() {
        if (stopped) return;
        stopped = true;
        monitoring = false;
        cancelReconnect();
        cancelHeartbeat();
        Connection old = connection;
        connection = null;
        IllegalStateException failure = new IllegalStateException("Electrum client stopped");
        publishConnection(false);
        if (old != null) {
            old.retired = true;
            old.serverVersion = null;
            old.protocolVersion = null;
            old.ready.completeExceptionally(failure);
            failPending(old, failure);
        }
        notificationListeners.clear();
        connectionListeners.clear();
        if (client != null) {
            client.close();
        }
    }

    /** Pure local read: never opens a connection, negotiates or sends an RPC. */
    public synchronized ElectrumServerDto serverInfo() {
        Connection current = connection;
        boolean ready = !stopped && current != null && current.socket != null && !current.retired;
        return new ElectrumServerDto(host, port, ssl, ready,
                ready ? current.serverVersion : null, ready ? current.protocolVersion : null);
    }

    /** Registers a non-blocking notification callback. Closing the registration removes it. */
    public synchronized AutoCloseable onNotification(Consumer<JsonObject> listener) {
        Objects.requireNonNull(listener, "listener");
        Consumer<JsonObject> registration = listener::accept;
        if (!stopped) notificationListeners.add(registration);
        return () -> notificationListeners.remove(registration);
    }

    /** Registers a non-blocking state-change callback (no replay of the current state). */
    public synchronized AutoCloseable onConnectionChange(Consumer<Boolean> listener) {
        Objects.requireNonNull(listener, "listener");
        Consumer<Boolean> registration = listener::accept;
        if (!stopped) connectionListeners.add(registration);
        return () -> connectionListeners.remove(registration);
    }

    /** Opts into proactive connections, bounded retries and heartbeats; idempotent. */
    public synchronized void startMonitoring() {
        if (stopped || monitoring) return;
        monitoring = true;
        nextReconnectDelay = reconnectInitialDelayMillis;
        Connection current = connection();
        if (current.socket != null) scheduleHeartbeat(current);
    }

    private synchronized Connection connection() {
        if (stopped) throw new IllegalStateException("Electrum client stopped");
        if (connection != null) return connection;
        cancelReconnect();
        Connection attempt = new Connection();
        connection = attempt;
        try {
            client.connect(port, host).onComplete(ar -> {
                synchronized (ElectrumClient.this) {
                    if (stopped || connection != attempt) {
                        if (ar.succeeded()) ar.result().close();
                        return;
                    }
                    attempt.connecting = false;
                    if (attempt.retired) {
                        if (ar.succeeded()) ar.result().close();
                        connection = null;
                        scheduleReconnect();
                        return;
                    }
                    if (ar.failed()) {
                        lost(attempt, ar.cause());
                        return;
                    }
                    attempt.socket = ar.result();
                    setup(attempt);
                    // Send once on this actual socket, before releasing application callers.
                    // Metadata is optional: never wait for it in ready or connection listeners.
                    negotiateVersion(attempt);
                    if (connection != attempt || stopped) return;
                    nextReconnectDelay = reconnectInitialDelayMillis;
                    publishConnection(true);
                    if (connection == attempt && !stopped) {
                        attempt.ready.complete(attempt.socket);
                        scheduleHeartbeat(attempt);
                    }
                }
            });
        } catch (RuntimeException failure) {
            attempt.connecting = false;
            lost(attempt, failure);
        }
        return attempt;
    }

    private void publishConnection(boolean state) {
        if (Objects.equals(connected, state)) return;
        if (state) LOG.debug("Electrum connection opened");
        else if (Boolean.TRUE.equals(connected)) LOG.debug("Electrum connection closed");
        connected = state;
        connectionListeners.forEach(listener -> {
            try {
                listener.accept(state);
            } catch (Exception ignored) {
                // Listener failures must not affect transport or expose wallet data in logs.
            }
        });
    }

    private void setup(Connection owner) {
        NetSocket sock = owner.socket;
        RecordParser parser = RecordParser.newDelimited("\n", buffer -> {
            JsonObject resp;
            try {
                resp = new JsonObject(buffer);
            } catch (Exception e) {
                return;
            }
            Object idVal = resp.getValue("id");
            if (!(idVal instanceof Number number)) {
                if (!resp.containsKey("id")) dispatchNotification(owner, resp);
                return;
            }
            int id = number.intValue();
            if (number.doubleValue() != id) return;
            synchronized (ElectrumClient.this) {
                Pending request = pending.get(id);
                if (connection != owner || request == null || request.connection() != owner
                        || !pending.remove(id, request)) return;
                Object err = resp.getValue("error");
                if (err != null) {
                    // Server-provided errors may contain scripts or other wallet data.
                    request.emitter().fail(new RuntimeException("Electrum error"));
                } else {
                    request.emitter().complete(resp);
                }
            }
        });
        sock.handler(parser);
        sock.closeHandler(v -> lost(owner, new RuntimeException("Electrum connection closed")));
        sock.exceptionHandler(t -> lost(owner, t));
    }

    private synchronized void dispatchNotification(Connection owner, JsonObject message) {
        if (stopped || connection != owner || message.containsKey("result") || message.containsKey("error")) return;
        if (!(message.getValue("params") instanceof JsonArray params)) return;
        Object method = message.getValue("method");
        boolean valid = ElectrumMethod.SCRIPTHASH_SUBSCRIBE.wire().equals(method)
                && params.size() == 2 && params.getValue(0) instanceof String
                && (params.getValue(1) == null || params.getValue(1) instanceof String);
        valid |= ElectrumMethod.HEADERS_SUBSCRIBE.wire().equals(method)
                && params.size() == 1 && params.getValue(0) instanceof JsonObject;
        if (!valid) return;
        LOG.debug(ElectrumMethod.SCRIPTHASH_SUBSCRIBE.wire().equals(method)
            ? "Electrum notification: address changed" : "Electrum notification: new block");
        notificationListeners.forEach(listener -> {
            try {
                listener.accept(message.copy());
            } catch (Exception ignored) {
                // Isolate both listener exceptions and mutations from the other listeners.
            }
        });
    }

    private synchronized void lost(Connection owner, Throwable failure) {
        if (connection != owner || owner.retired) return;
        owner.retired = true;
        owner.serverVersion = null;
        owner.protocolVersion = null;
        LOG.warnf("Electrum transport failure: type=%s", failure.getClass().getSimpleName());
        // Vert.x connect futures are not cancellable. Keep the attempt reserved until its
        // bounded connect completes, then discard its socket and retry without overlap.
        if (!owner.connecting) connection = null;
        cancelHeartbeat();
        publishConnection(false);
        owner.ready.completeExceptionally(failure);
        failPending(owner, failure);
        if (owner.socket != null) owner.socket.close();
        scheduleReconnect();
    }

    private void failPending(Connection owner, Throwable failure) {
        List.copyOf(pending.entrySet()).forEach(entry -> {
            Pending request = entry.getValue();
            if (request.connection() == owner && pending.remove(entry.getKey(), request)) {
                request.emitter().fail(failure);
            }
        });
    }

    private void cancelReconnect() {
        if (reconnectTimer != -1) vertx.cancelTimer(reconnectTimer);
        reconnectTimer = -1;
    }

    private void scheduleReconnect() {
        if (!monitoring || stopped || connection != null || reconnectTimer != -1) return;
        long delay = Math.min(nextReconnectDelay, reconnectMaxDelayMillis);
        nextReconnectDelay = Math.min(delay * 2, reconnectMaxDelayMillis);
        LOG.debugf("Electrum reconnect scheduled: delayMs=%d", delay);
        reconnectTimer = vertx.setTimer(delay, id -> {
            synchronized (ElectrumClient.this) {
                if (reconnectTimer != id) return;
                reconnectTimer = -1;
                if (monitoring && !stopped) connection();
            }
        });
    }

    private void cancelHeartbeat() {
        if (heartbeatTimer != -1) vertx.cancelTimer(heartbeatTimer);
        heartbeatTimer = -1;
        if (heartbeatRequest != null) heartbeatRequest.cancel();
        heartbeatRequest = null;
    }

    private void scheduleHeartbeat(Connection owner) {
        if (!monitoring || stopped || connection != owner || heartbeatTimer != -1) return;
        heartbeatTimer = vertx.setTimer(heartbeatIntervalMillis, id -> {
            synchronized (ElectrumClient.this) {
                if (heartbeatTimer != id || connection != owner || stopped) return;
                heartbeatTimer = -1;
                heartbeatRequest = call(ElectrumMethod.SERVER_PING).subscribe().with(
                        ignored -> {
                            synchronized (ElectrumClient.this) {
                                if (connection == owner) {
                                    heartbeatRequest = null;
                                    scheduleHeartbeat(owner);
                                }
                            }
                        }, failure -> lost(owner, failure));
            }
        });
    }

    private void negotiateVersion(Connection owner) {
        request(owner, Uni.createFrom().item(owner.socket), false,
                ElectrumMethod.SERVER_VERSION.wire(), "Wallet Viewer", "1.4")
                .invoke(response -> {
                    Object value = response.getValue("result");
                    if (!(value instanceof JsonArray versions) || versions.size() != 2
                            || !(versions.getValue(0) instanceof String serverVersion) || serverVersion.isBlank()
                            || !(versions.getValue(1) instanceof String protocolVersion) || protocolVersion.isBlank()) {
                        throw new IllegalArgumentException("Invalid Electrum metadata");
                    }
                    synchronized (ElectrumClient.this) {
                        if (!stopped && connection == owner && !owner.retired) {
                            owner.serverVersion = serverVersion;
                            owner.protocolVersion = protocolVersion;
                        }
                    }
                }).subscribe().with(ignored -> { }, failure -> {
                    // No exception message, server payload, retry or transport reset here.
                    LOG.warnf("Electrum metadata unavailable: type=%s", failure.getClass().getSimpleName());
                });
    }

    /** Sends a JSON-RPC request and returns the full response object. */
    public Uni<JsonObject> call(ElectrumMethod method, Object... params) {
        return call(method.wire(), params);
    }

    /** Transport-level entry point; production callers use the {@link ElectrumMethod} overload. */
    public Uni<JsonObject> call(String method, Object... params) {
        // Each subscription needs a fresh id and the current connection (including retries).
        return Uni.createFrom().deferred(() -> {
            Connection owner = connection();
            // Do not cancel the shared connect future when an individual caller cancels.
            Uni<NetSocket> ready = Uni.createFrom().emitter(em -> owner.ready.whenComplete((sock, failure) -> {
                if (failure != null) em.fail(failure);
                else em.complete(sock);
            }));
            return request(owner, ready, true, method, params);
        });
    }

    private Uni<JsonObject> request(Connection owner, Uni<NetSocket> socket, boolean retireOnTimeout,
                                    String method, Object... params) {
        return Uni.createFrom().deferred(() -> {
            long started = System.nanoTime();
            int id = counter.incrementAndGet();
            String payload = new JsonObject()
                    .put("id", id)
                    .put("method", method)
                    .put("params", new JsonArray(List.of(params)))
                    .encode() + "\n";
            return socket.flatMap(sock ->
                    Uni.createFrom().<JsonObject>emitter(em -> {
                        synchronized (ElectrumClient.this) {
                            if (stopped || connection != owner || owner.retired) {
                                em.fail(new IllegalStateException("Electrum connection closed"));
                                return;
                            }
                            Pending request = new Pending(owner, em);
                            pending.put(id, request);
                            em.onTermination(() -> pending.remove(id, request));
                            sock.write(payload).onFailure(failure -> lost(owner, failure));
                        }
                    })
            ).ifNoItem().after(requestTimeout).fail()
                    .onFailure(TimeoutException.class).invoke(failure -> {
                        LOG.warnf("Electrum RPC timeout: method=%s id=%d type=TimeoutException", safeMethod(method), id);
                        synchronized (ElectrumClient.this) {
                            if (retireOnTimeout && monitoring) lost(owner, failure);
                        }
                    })
                    .onItemOrFailure().invoke((response, failure) -> LOG.debugf(
                            "Electrum RPC: method=%s id=%d durationMs=%d outcome=%s",
                            safeMethod(method), id, (System.nanoTime() - started) / 1_000_000,
                            failure == null ? "success" : "failure"));
        });
    }

    /** Never log arbitrary method names supplied by a caller or peer. */
    private static String safeMethod(String method) {
        if (method == null) return "unknown";
        return ElectrumMethod.isKnown(method) ? method : "unknown";
    }
}
