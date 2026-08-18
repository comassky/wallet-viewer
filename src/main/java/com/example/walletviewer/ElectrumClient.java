package com.example.walletviewer;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reactive (Vert.x) client for the ElectrumX / electrs JSON-RPC protocol.
 * Uses a single persistent TCP connection; requests are matched to responses by id.
 */
@ApplicationScoped
public class ElectrumClient {

    @ConfigProperty(name = "electrum.host", defaultValue = "127.0.0.1")
    String host;
    @ConfigProperty(name = "electrum.port", defaultValue = "50001")
    int port;
    @ConfigProperty(name = "electrum.ssl", defaultValue = "false")
    boolean ssl;

    @Inject
    Vertx vertx;

    private NetClient client;
    private final Map<Integer, UniEmitter<? super JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile Uni<NetSocket> socketUni;

    @PostConstruct
    void init() {
        NetClientOptions opts = new NetClientOptions()
                .setReconnectAttempts(3)
                .setReconnectInterval(500);
        if (ssl) {
            opts.setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm("");
        }
        client = vertx.createNetClient(opts);
    }

    private Uni<NetSocket> socket() {
        Uni<NetSocket> s = socketUni;
        if (s == null) {
            synchronized (this) {
                if (socketUni == null) {
                    socketUni = Uni.createFrom().<NetSocket>emitter(em ->
                            client.connect(port, host).onComplete(ar -> {
                                if (ar.succeeded()) {
                                    setup(ar.result());
                                    em.complete(ar.result());
                                } else {
                                    socketUni = null;
                                    em.fail(ar.cause());
                                }
                            })
                    ).memoize().indefinitely();
                }
                s = socketUni;
            }
        }
        return s;
    }

    private void setup(NetSocket sock) {
        RecordParser parser = RecordParser.newDelimited("\n", buffer -> {
            JsonObject resp;
            try {
                resp = new JsonObject(buffer);
            } catch (Exception e) {
                return;
            }
            Object idVal = resp.getValue("id");
            if (idVal == null) {
                return; // subscription notification, ignore
            }
            int id = ((Number) idVal).intValue();
            UniEmitter<? super JsonObject> em = pending.remove(id);
            if (em == null) {
                return;
            }
            Object err = resp.getValue("error");
            if (err != null) {
                em.fail(new RuntimeException("Electrum error: " + err));
            } else {
                em.complete(resp);
            }
        });
        sock.handler(parser);
        sock.closeHandler(v -> {
            socketUni = null;
            failAll(new RuntimeException("Electrum connection closed"));
        });
        sock.exceptionHandler(t -> {
            socketUni = null;
            failAll(t);
        });
    }

    private void failAll(Throwable t) {
        for (Integer id : List.copyOf(pending.keySet())) {
            UniEmitter<? super JsonObject> em = pending.remove(id);
            if (em != null) {
                em.fail(t);
            }
        }
    }

    /**
     * Sends a JSON-RPC request and returns the full response object.
     */
    public Uni<JsonObject> call(String method, Object... params) {
        int id = counter.incrementAndGet();
        JsonObject req = new JsonObject()
                .put("id", id)
                .put("method", method)
                .put("params", new JsonArray(List.of(params)));
        String payload = req.encode() + "\n";
        return socket().flatMap(sock ->
                Uni.createFrom().<JsonObject>emitter(em -> {
                    pending.put(id, em);
                    sock.write(payload).onFailure(err -> {
                        pending.remove(id);
                        em.fail(err);
                    });
                })
        );
    }
}
