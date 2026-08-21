package com.example.walletviewer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Loopback-only Electrum fixture; all mutable protocol state is guarded by this instance. */
public class LiveWalletTestResource implements QuarkusTestResourceLifecycleManager {
    // Official BIP86 public test vector, not a configured or generated private wallet.
    public static final String XPUB = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ";
    public static final String ADDRESS = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr";
    public static final long VALUE = 50_000L;

    private final Map<NetSocket, Set<String>> subscriptions = new HashMap<>();
    private final Set<NetSocket> notified = new HashSet<>();
    private final CompletableFuture<Void> ownScriptSubscribed = new CompletableFuture<>();
    private Vertx vertx;
    private NetServer server;
    private String scriptHash;
    private String transactionId;
    private String transactionHex;
    private String status;
    private boolean credited;
    private boolean stopping;
    private int scans;
    private int histories;
    private int notifications;
    private int versions;
    private Throwable protocolFailure;

    @Override
    public Map<String, String> start() {
        Script output = ScriptBuilder.createOutputScript(SegwitAddress.fromBech32(MainNetParams.get(), ADDRESS));
        scriptHash = Utils.HEX.encode(Utils.reverseBytes(Sha256Hash.hash(output.getProgram())));
        Transaction transaction = new Transaction(MainNetParams.get());
        // A synthetic, unsigned input: no previous transaction or network lookup is necessary.
        transaction.addInput(Sha256Hash.wrap("11".repeat(32)), 0, new Script(new byte[]{0x51}));
        transaction.addOutput(Coin.valueOf(VALUE), output);
        transactionId = transaction.getTxId().toString();
        transactionHex = Utils.HEX.encode(transaction.bitcoinSerialize());
        status = Utils.HEX.encode(Sha256Hash.hash((transactionId + ":0:").getBytes(StandardCharsets.UTF_8)));

        vertx = Vertx.vertx();
        try {
            server = vertx.createNetServer().connectHandler(this::accept);
            server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            return Map.ofEntries(
                    Map.entry("wallet.xpub", XPUB),
                    Map.entry("wallet.script-type", "p2tr"),
                    Map.entry("wallet.network", "mainnet"),
                    Map.entry("wallet.gap-limit", "2"),
                    Map.entry("wallet.max-addresses", "4"),
                    Map.entry("electrum.host", "127.0.0.1"),
                    Map.entry("electrum.port", Integer.toString(server.actualPort())),
                    Map.entry("electrum.ssl", "false"),
                    Map.entry("electrum.request-timeout", "5s"),
                    // Prices are not exercised; even an accidental call must stay on loopback.
                    Map.entry("wallet.prices-url", "http://127.0.0.1:1/unused-prices"),
                    Map.entry("quarkus.http.host", "localhost"),
                    Map.entry("quarkus.http.root-path", "/"),
                    Map.entry("quarkus.http.test-port", "0"),
                    Map.entry("quarkus.quinoa.enabled", "false"),
                    Map.entry("quarkus.devservices.enabled", "false"));
        } catch (Exception failure) {
            try { stop(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Cannot start local Electrum fixture", failure);
        }
    }

    @Override
    public void inject(TestInjector injector) {
        // Test-resource injection, deliberately not CDI and not a static singleton.
        injector.injectIntoFields(this, new TestInjector.MatchesType(LiveWalletTestResource.class));
    }

    private synchronized void accept(NetSocket socket) {
        subscriptions.put(socket, new HashSet<>());
        socket.closeHandler(ignored -> remove(socket));
        socket.exceptionHandler(this::recordFailure);
        socket.handler(RecordParser.newDelimited("\n", buffer -> {
            try { respond(socket, new JsonObject(buffer)); }
            catch (RuntimeException failure) { recordFailure(failure); socket.close(); }
        }));
    }

    private synchronized void remove(NetSocket socket) {
        subscriptions.remove(socket);
        notified.remove(socket);
    }

    private synchronized void recordFailure(Throwable failure) {
        if (!stopping && protocolFailure == null) protocolFailure = failure;
    }

    private void write(NetSocket socket, JsonObject message) {
        socket.write(message.encode() + "\n").onFailure(this::recordFailure);
    }

    private synchronized void respond(NetSocket socket, JsonObject request) {
        String method = request.getString("method");
        JsonArray params = request.getJsonArray("params", new JsonArray());
        String hash = method.startsWith("blockchain.scripthash.") ? params.getString(0) : null;
        boolean funded = credited && scriptHash.equals(hash);
        Object result;
        switch (method) {
            case "server.version" -> {
                if (!new JsonArray().add("Wallet Viewer").add("1.4").equals(params)) {
                    throw new IllegalStateException("Unexpected version negotiation parameters");
                }
                versions++;
                result = new JsonArray().add("fixture 1.0").add("1.4");
            }
            case "server.ping" -> result = null;
            case "blockchain.headers.subscribe" -> {
                scans++;
                result = new JsonObject().put("height", 100);
            }
            case "blockchain.scripthash.subscribe" -> {
                subscriptions.get(socket).add(hash);
                result = funded ? status : null;
            }
            case "blockchain.scripthash.get_history" -> {
                if (!subscriptions.get(socket).contains(hash)) {
                    throw new IllegalStateException("History requested before subscription: " + hash);
                }
                histories++;
                result = funded ? new JsonArray().add(new JsonObject()
                        .put("tx_hash", transactionId).put("height", 0)) : new JsonArray();
            }
            case "blockchain.scripthash.get_balance" -> result = new JsonObject()
                    .put("confirmed", 0L).put("unconfirmed", funded ? VALUE : 0L);
            case "blockchain.scripthash.listunspent" -> result = funded ? new JsonArray().add(new JsonObject()
                    .put("tx_hash", transactionId).put("tx_pos", 0).put("value", VALUE).put("height", 0))
                    : new JsonArray();
            case "blockchain.transaction.get" -> {
                if (!credited || !transactionId.equals(params.getString(0))) {
                    throw new IllegalStateException("Unexpected transaction lookup");
                }
                result = transactionHex;
            }
            default -> throw new IllegalStateException("Unexpected Electrum method: " + method);
        }
        // Subscription acknowledgements carry an id, never an automatic notification.
        write(socket, new JsonObject().put("jsonrpc", "2.0").put("id", request.getValue("id"))
                .put("result", result));
        if ("blockchain.scripthash.subscribe".equals(method) && scriptHash.equals(hash)) {
            ownScriptSubscribed.complete(null);
        }
    }

    /** Waits for a real subscription, then publishes exactly one credit notification per socket. */
    public void credit() throws Exception {
        ownScriptSubscribed.get(10, TimeUnit.SECONDS);
        synchronized (this) {
            assertHealthy();
            if (credited) return;
            if (subscriptions.values().stream().noneMatch(hashes -> hashes.contains(scriptHash))) {
                throw new IllegalStateException("No connected subscriber for the fixture address");
            }
            credited = true;
            subscriptions.forEach((socket, hashes) -> {
                if (hashes.contains(scriptHash) && notified.add(socket)) {
                    notifications++;
                    write(socket, new JsonObject().put("jsonrpc", "2.0")
                            .put("method", "blockchain.scripthash.subscribe")
                            .put("params", new JsonArray().add(scriptHash).add(status)));
                }
            });
        }
    }

    public synchronized int scanCount() { return scans; }
    public synchronized int historyCount() { return histories; }
    public synchronized int notificationCount() { return notifications; }
    public synchronized int versionCount() { return versions; }
    public int port() { return server.actualPort(); }
    public synchronized String transactionId() { return transactionId; }

    public synchronized void assertHealthy() {
        if (protocolFailure != null) throw new AssertionError("Local Electrum fixture failed", protocolFailure);
    }

    @Override
    public void stop() {
        synchronized (this) { stopping = true; }
        if (vertx == null) return;
        try {
            // Closing Vert.x closes the server and all accepted sockets as well.
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Cannot stop local Electrum fixture", failure);
        } finally {
            synchronized (this) { subscriptions.clear(); notified.clear(); }
            vertx = null;
        }
    }
}