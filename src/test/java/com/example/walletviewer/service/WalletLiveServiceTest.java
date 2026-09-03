package com.example.walletviewer.service;

import com.example.walletviewer.derivation.HdWallet;
import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.AddressInfo;
import com.example.walletviewer.model.BalanceDto;
import com.example.walletviewer.model.ReceiveAddressDto;
import com.example.walletviewer.model.TransactionDto;
import com.example.walletviewer.model.UtxoDto;
import com.example.walletviewer.model.WalletSnapshot;
import com.example.walletviewer.model.WalletState;
import com.example.walletviewer.support.TestLogCapture;
import com.example.walletviewer.web.WalletResource;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.ServiceUnavailableException;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

import static com.example.walletviewer.support.TestFields.setField;
import static org.junit.jupiter.api.Assertions.*;

class WalletLiveServiceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final WalletLiveService live = new WalletLiveService();
    private final ScannerStub scanner = new ScannerStub();
    private final ElectrumStub electrum = new ElectrumStub();
    private final BlockingQueue<Observation> events = new LinkedBlockingQueue<>();
    private ScheduledExecutorService worker;
    private long lastVersion = -1;

    @BeforeEach
    void setUp() throws Exception {
        live.scanner = scanner;
        live.electrum = electrum;
        live.demo = new DemoService();
        // A barrier on the real single writer lets assertions wait for its finally block,
        // not merely for listener delivery. No production timing override is needed.
        worker = (ScheduledExecutorService) field("worker").get(live);
        live.listen(state -> events.add(new Observation(state, live.current())));
    }

    @AfterEach
    void tearDown() throws Exception {
        live.stop();
        assertTrue(worker.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "Coordinator worker did not terminate");
    }

    @Test
    void startupReplaysLoadingThenPublishesCommittedLiveSnapshot() throws Exception {
        WalletState loading = next("loading", null);
        assertEquals(0, loading.version());
        assertNotNull(loading.message());
        assertEquals(0, scanner.calls.get());
        assertFalse(electrum.started);

        live.start(new StartupEvent());
        assertTrue(electrum.started);
        assertTrue(electrum.listenersRegisteredAtStart);
        next("syncing", null);
        CompletableFuture<WalletSnapshot> scan = take(scanner.pending);
        WalletSnapshot snapshot = snapshot(100);
        scan.complete(snapshot);
        WalletState state = next("live", snapshot);
        assertNull(state.message());
        idle();
        assertSame(state, live.current());
        assertEquals(1, scanner.calls.get());

        BlockingQueue<WalletState> replay = new LinkedBlockingQueue<>();
        try (AutoCloseable ignored = live.listen(replay::add)) {
            // Replay is synchronous, so poll rather than waiting for asynchronous delivery.
            assertSame(state, replay.poll());
        }
    }

    @Test
    void restSnapshotFailsWith503WithoutDataAndReadsCacheWithoutScanning() throws Exception {
        WalletResource resource = new WalletResource();
        setField(WalletResource.class, resource, "live", live);
        setField(WalletResource.class, resource, "service", scanner);
        Uni<WalletSnapshot> deferred = resource.snapshot();
        assertEquals(503, assertThrows(ServiceUnavailableException.class,
                () -> deferred.await().atMost(TIMEOUT)).getResponse().getStatus());
        assertEquals(503, assertThrows(ServiceUnavailableException.class,
                () -> live.snapshot().await().atMost(TIMEOUT)).getResponse().getStatus());
        assertEquals(0, scanner.calls.get());

        WalletSnapshot snapshot = startWithSnapshot();
        for (int i = 0; i < 3; i++) {
            assertSame(snapshot, deferred.await().atMost(TIMEOUT));
            assertSame(snapshot, resource.snapshot().await().atMost(TIMEOUT));
            assertSame(snapshot, live.snapshot().await().atMost(TIMEOUT));
        }
        idle();
        assertEquals(1, scanner.calls.get());
        assertTrue(scanner.pending.isEmpty());
    }

    @Test
    void notificationRescansAndReplacesCacheOnlyAfterCompletion() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.notifyWallet();
        next("syncing", previous);
        CompletableFuture<WalletSnapshot> scan = take(scanner.pending);
        assertSame(previous, live.snapshot().await().atMost(TIMEOUT));
        WalletSnapshot updated = snapshot(200);
        scan.complete(updated);
        next("live", updated);
        idle();
        assertEquals(2, scanner.calls.get());
        assertSame(updated, live.snapshot().await().atMost(TIMEOUT));
    }

    @Test
    void burstBeforeScanCoalescesIntoOneRescan() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var barrier = worker.submit(() -> {
            entered.countDown();
            try {
                if (!release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Test did not release worker barrier");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        try {
            assertTrue(entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            for (int i = 0; i < 100; i++) electrum.notifyWallet();
            assertEquals(1, scanner.calls.get());
        } finally {
            release.countDown();
        }
        barrier.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        next("syncing", previous);
        WalletSnapshot updated = snapshot(300);
        take(scanner.pending).complete(updated);
        next("live", updated);
        idle();
        assertEquals(2, scanner.calls.get());
        assertNull(scheduled(), "No redundant pass should remain scheduled");
        assertTrue(events.isEmpty());
    }

    @Test
    void notificationsDuringScanDiscardResultAndCoalesceIntoOneLatestPass() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.notifyWallet();
        next("syncing", previous);
        CompletableFuture<WalletSnapshot> staleScan = take(scanner.pending);
        for (int i = 0; i < 100; i++) electrum.notifyWallet();
        assertEquals(2, scanner.calls.get(), "Scans must remain serialized");
        staleScan.complete(snapshot(999));

        // The next event must be syncing, never a live event for the stale result.
        next("syncing", previous);
        CompletableFuture<WalletSnapshot> latestScan = take(scanner.pending);
        WalletSnapshot latest = snapshot(400);
        latestScan.complete(latest);
        WalletState state = next("live", latest);
        assertEquals(5, state.version());
        idle();
        assertEquals(3, scanner.calls.get());
        assertNull(scheduled());
        assertTrue(events.isEmpty());
    }

    @Test
    void disconnectRetainsSnapshotAndReconnectInvalidatesInFlightScan() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.notifyWallet();
        next("syncing", previous);
        CompletableFuture<WalletSnapshot> obsolete = take(scanner.pending);
        electrum.connection(false);
        WalletState offline = next("offline", previous);
        assertNotNull(offline.message());
        assertSame(previous, live.snapshot().await().atMost(TIMEOUT));
        electrum.connection(true);
        assertEquals(2, scanner.calls.get());
        obsolete.complete(snapshot(999));

        next("syncing", previous);
        WalletSnapshot latest = snapshot(500);
        take(scanner.pending).complete(latest);
        assertEquals(6, next("live", latest).version());
        idle();
        assertEquals(3, scanner.calls.get());
        assertNull(scheduled());
        assertTrue(events.isEmpty());
    }

    @Test
    void disconnectWhileIdleKeepsCacheAndReconnectStartsFreshScan() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.connection(false);
        next("offline", previous);
        electrum.notifyWallet();
        idle();
        assertNull(scheduled(), "Notifications must not schedule scans while offline");
        assertEquals(1, scanner.calls.get());
        electrum.connection(true);
        next("syncing", previous);
        WalletSnapshot latest = snapshot(600);
        take(scanner.pending).complete(latest);
        next("live", latest);
        idle();
        assertEquals(2, scanner.calls.get());
    }

    @Test
    void closingListenerRemovesCallbackWithoutRemovingOtherListeners() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        BlockingQueue<WalletState> removed = new LinkedBlockingQueue<>();
        AutoCloseable registration = live.listen(removed::add);
        assertSame(live.current(), removed.poll());
        registration.close();
        registration.close();
        electrum.connection(false);
        next("offline", previous);
        assertTrue(removed.isEmpty());
    }

    @Test
    void listenerThrowingDuringImmediateReplayIsUnregistered() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("listener failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> live.listen(state -> {
            calls.incrementAndGet();
            throw failure;
        })));
        startWithSnapshot();
        assertEquals(1, calls.get());
    }

    @Test
    void listenerThrowingOnUpdateIsRemovedAndDoesNotBreakPublication() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        live.listen(state -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("listener failed");
        });
        startWithSnapshot();
        assertEquals(2, calls.get());
    }

    @Test
    void failedScanRetainsCompleteSnapshotAndStopPreventsScheduledRetry() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.notifyWallet();
        next("syncing", previous);
        take(scanner.pending).completeExceptionally(new IllegalStateException("Electrum scan failed"));
        WalletState error = next("error", previous);
        assertNotNull(error.message());
        assertSame(previous, live.snapshot().await().atMost(TIMEOUT));
        assertEquals(100, live.current().snapshot().balance().total());
        assertFalse(live.current().snapshot().utxos().isEmpty());
        assertFalse(live.current().snapshot().transactions().isEmpty());
        idle();
        ScheduledFuture<?> retry = scheduled();
        assertNotNull(retry, "Failure should schedule a retry");
        assertFalse(retry.isDone());
        live.stop();
        assertTrue(worker.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertTrue(worker.isShutdown());
        assertEquals(2, scanner.calls.get());
        assertSame(error, live.current());
        assertTrue(events.isEmpty());
    }

    @Test
    void stopDuringScanClosesTransportCallbacksAndPreventsFurtherWork() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.notifyWallet();
        WalletState syncing = next("syncing", previous);
        CompletableFuture<WalletSnapshot> pending = take(scanner.pending);
        Consumer<JsonObject> lateNotification = electrum.notifications.getFirst();
        Consumer<Boolean> lateConnection = electrum.connections.getFirst();
        live.stop();
        pending.complete(snapshot(999));
        assertTrue(worker.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertTrue(electrum.notifications.isEmpty());
        assertTrue(electrum.connections.isEmpty());
        assertEquals(1, electrum.notificationCloses.get());
        assertEquals(1, electrum.connectionCloses.get());
        // Even callbacks captured by the transport before removal cannot restart work.
        lateNotification.accept(new JsonObject());
        lateConnection.accept(false);
        lateConnection.accept(true);
        assertSame(syncing, live.current());
        assertEquals(2, scanner.calls.get());
        assertTrue(events.isEmpty());
        assertTrue(worker.isTerminated());
    }

    @Test
    void addressNotificationsLogOnlyLocallyKnownAddressesAndTolerateInvalidParams() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        electrum.connection(false);
        next("offline", previous);
        // Populate the real registry using real local derivations, not notification data.
        WalletService watched = watchedAddresses();
        AddressInfo receive = watched.derive(0, 0);
        AddressInfo change = watched.derive(1, 0);
        watched.wallet = null;
        watched.electrum = null; // The callback must neither derive nor perform RPCs.
        live.scanner = watched;
        AtomicLong changes = (AtomicLong) field("changes").get(live);
        long revision = changes.get();
        String payload = "remote-status\r\nforged-log tx-secret zpub-secret balance=987654321";
        String unknownHash = "f".repeat(64);

        try (TestLogCapture logs = new TestLogCapture(WalletLiveService.class)) {
            electrum.notifyWallet(new JsonArray().add(receive.scripthash).add(payload));
            electrum.notifyWallet(new JsonArray().add(change.scripthash).add(null));
            assertEquals(List.of("Electrum address notification: address=" + receive.address,
                    "Electrum address notification: address=" + change.address), logs.messages());

            List<JsonObject> malformedOrUnknown = List.of(
                    walletNotification(new JsonArray().add(unknownHash).add(payload)),
                    walletNotification(new JsonArray().add(payload).add(null)),
                    walletNotification(new JsonArray().add("a".repeat(63) + "\n").add(null)),
                    walletNotification(new JsonArray().add("a".repeat(65)).add(null)),
                    walletNotification(null), // Existing method-only stubs remain valid callbacks.
                    walletNotification(payload),
                    walletNotification(new JsonObject().put("address", payload)),
                    walletNotification(new JsonArray()),
                    walletNotification(new JsonArray().add(receive.scripthash)),
                    walletNotification(new JsonArray().add(receive.scripthash).add(null).add(payload)),
                    walletNotification(new JsonArray().add(null).add(null)),
                    walletNotification(new JsonArray().add(123).add(null)),
                    walletNotification(new JsonArray().add(new JsonObject().put("address", payload)).add(null)),
                    walletNotification(new JsonArray().add(receive.scripthash).add(new JsonObject())),
                    walletNotification(new JsonArray().add(receive.scripthash).add(123)));
            for (JsonObject notification : malformedOrUnknown) {
                assertDoesNotThrow(() -> electrum.notifications.forEach(listener -> listener.accept(notification)));
            }
            assertEquals(malformedOrUnknown.size(), logs.messages().stream()
                    .filter(m -> m.equals("Electrum address notification: address=unknown")).count());
            assertEquals(2 + malformedOrUnknown.size(), logs.entries().size());
            assertEquals(revision + 2 + malformedOrUnknown.size(), changes.get(),
                    "Address resolution must not change notification revision tracking");
            assertNull(watched.knownAddressForScripthash(unknownHash), "Never learn remote identities");

            // Headers and invalid method types must not produce an address log.
            electrum.notifications.forEach(listener -> {
                listener.accept(new JsonObject().put("method", "blockchain.headers.subscribe"));
                listener.accept(new JsonObject().put("method", new JsonArray().add(payload)));
                listener.accept(new JsonObject());
                listener.accept(null);
            });
            assertEquals(2 + malformedOrUnknown.size(), logs.entries().size());
            assertTrue(logs.entries().stream().allMatch(entry -> entry.level() == Level.INFO.intValue()
                    && entry.thrown() == null));
            assertTrue(logs.messages().stream().noneMatch(m -> m.contains(payload) || m.contains(unknownHash)
                    || m.contains(receive.scripthash) || m.contains(change.scripthash)
                    || m.contains("tx-secret") || m.contains("zpub-secret") || m.contains("987654321")
                    || m.contains("\r") || m.contains("\n")));
            // Only the two intentional address logs may contain a wallet address.
            assertTrue(logs.messages().stream().skip(2)
                    .noneMatch(m -> m.contains(receive.address) || m.contains(change.address)));
        }
        idle();
        assertNull(scheduled(), "Offline notifications must still not schedule scans");
        assertEquals(1, scanner.calls.get());
    }

    @Test
    void confirmationChangesDoNotCountHistoricalTransactionIdsAsNew() throws Exception {
        WalletSnapshot previous = startWithSnapshot();
        TransactionDto historical = previous.transactions().getFirst();
        WalletSnapshot confirmed = new WalletSnapshot(previous.balance(), previous.utxos(),
                List.of(new TransactionDto(historical.txid(), 100, 100, 0, 11, 2, 123L, "received")),
                previous.receiveAddress());
        try (TestLogCapture logs = new TestLogCapture(WalletLiveService.class)) {
            electrum.notifyWallet(); // No params: must still rescan as before.
            next("syncing", previous);
            take(scanner.pending).complete(confirmed);
            next("live", confirmed);
            idle();
            assertTrue(logs.messages().contains("Electrum address notification: address=unknown"));
            assertTrue(logs.messages().stream().anyMatch(m -> m.endsWith("outcome=success newTransactions=0")));
            assertTrue(logs.messages().stream().noneMatch(m -> m.contains(historical.txid())
                    || m.contains("test-address") || m.contains("test-path")));
        }
    }

    @Test
    void scanLogsCountOnlyNewTransactionIdsAfterAnExistingSnapshotAndHideFailures() throws Exception {
        try (TestLogCapture logs = new TestLogCapture(WalletLiveService.class)) {
            WalletSnapshot previous = startWithSnapshot();
            List<String> initial = logs.messages().stream().filter(m -> m.startsWith("Wallet scan completed:")).toList();
            assertEquals(1, initial.size());
            assertTrue(initial.getFirst().contains("durationMs="));
            assertTrue(initial.getFirst().endsWith("outcome=success"));
            assertFalse(initial.getFirst().contains("newTransactions="), "First load has no comparison baseline");

            electrum.notifyWallet();
            next("syncing", previous);
            WalletSnapshot updated = snapshot(200);
            take(scanner.pending).complete(updated);
            next("live", updated);
            idle();
            assertTrue(logs.messages().stream().anyMatch(m -> m.endsWith("outcome=success newTransactions=1")));

            electrum.notifyWallet();
            next("syncing", updated);
            take(scanner.pending).complete(updated);
            next("live", updated);
            idle();
            assertTrue(logs.messages().stream().anyMatch(m -> m.endsWith("outcome=success newTransactions=0")));

            electrum.notifyWallet();
            next("syncing", updated);
            take(scanner.pending).completeExceptionally(new IllegalStateException("secret-server-payload"));
            next("error", updated);
            idle();
            assertTrue(logs.messages().contains("Wallet sync failure: type=IllegalStateException"));
            assertTrue(logs.messages().stream().anyMatch(m -> m.endsWith("outcome=failure")));
            assertEquals(4, logs.messages().stream().filter(m -> m.equals("Wallet scan started")).count());
            assertTrue(logs.messages().stream().noneMatch(m -> m.contains("secret-server-payload")
                    || m.contains("tx-") || m.contains("test-address") || m.contains("test-path")));
            assertTrue(logs.entries().stream().allMatch(entry -> entry.thrown() == null));
        }
    }

    private static JsonObject walletNotification(Object params) {
        JsonObject notification = new JsonObject().put("method", "blockchain.scripthash.subscribe");
        if (params != null) notification.put("params", params);
        return notification;
    }

    private static WalletService watchedAddresses() {
        WalletService service = new WalletService();
        HdWallet wallet = new HdWallet();
        setField(HdWallet.class, wallet, "extPub",
                HDKeyDerivation.createMasterPrivateKey(new byte[32]).serializePubB58(MainNetParams.get()));
        setField(HdWallet.class, wallet, "network", "mainnet");
        setField(HdWallet.class, wallet, "scriptTypeCfg", "p2wpkh");
        service.wallet = wallet;
        service.gapLimit = 1;
        service.maxAddresses = 1;
        service.validateConfiguration();
        service.electrum = new ElectrumClient() {
            @Override
            public Uni<JsonObject> call(String method, Object... params) {
                Object result = switch (method) {
                    case "blockchain.headers.subscribe" -> new JsonObject().put("height", 100);
                    case "blockchain.scripthash.subscribe" -> null;
                    case "blockchain.scripthash.get_history" -> new JsonArray();
                    default -> throw new AssertionError("Unexpected RPC: " + method);
                };
                return Uni.createFrom().item(new JsonObject().put("result", result));
            }
        };
        service.scan().await().atMost(TIMEOUT);
        return service;
    }

    private WalletSnapshot startWithSnapshot() throws Exception {
        next("loading", null);
        live.start(new StartupEvent());
        next("syncing", null);
        WalletSnapshot snapshot = snapshot(100);
        take(scanner.pending).complete(snapshot);
        next("live", snapshot);
        idle();
        return snapshot;
    }

    private WalletState next(String status, WalletSnapshot snapshot) throws Exception {
        Observation observation = take(events);
        WalletState state = observation.state();
        assertSame(state, observation.cached(), "Cache must be committed before listener delivery");
        assertEquals(status, state.status());
        assertSame(snapshot, state.snapshot());
        assertEquals(++lastVersion, state.version(), "Published versions must increase monotonically");
        return state;
    }

    private void idle() throws Exception {
        worker.submit(() -> { }).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private ScheduledFuture<?> scheduled() throws Exception {
        synchronized (live) {
            return (ScheduledFuture<?>) field("scheduled").get(live);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = WalletLiveService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(value, "Timed out waiting for coordinator event or scan subscription");
        return value;
    }

    private static WalletSnapshot snapshot(long value) {
        return new WalletSnapshot(new BalanceDto(value, 0, value),
                List.of(new UtxoDto("tx-" + value, 0, value, 10, 1, "test-address")),
                List.of(new TransactionDto("tx-" + value, value, value, 0, 10, 1, null, "received")),
                new ReceiveAddressDto(0, "test-address", "test-path"));
    }

    private record Observation(WalletState state, WalletState cached) { }

    private static final class ScannerStub extends WalletService {
        final AtomicInteger calls = new AtomicInteger();
        final BlockingQueue<CompletableFuture<WalletSnapshot>> pending = new LinkedBlockingQueue<>();

        @Override
        public Uni<WalletSnapshot> scan() {
            return Uni.createFrom().deferred(() -> {
                CompletableFuture<WalletSnapshot> result = new CompletableFuture<>();
                calls.incrementAndGet();
                pending.add(result);
                return Uni.createFrom().completionStage(result);
            });
        }
    }

    private static final class ElectrumStub extends ElectrumClient {
        final List<Consumer<JsonObject>> notifications = new CopyOnWriteArrayList<>();
        final List<Consumer<Boolean>> connections = new CopyOnWriteArrayList<>();
        final AtomicInteger notificationCloses = new AtomicInteger();
        final AtomicInteger connectionCloses = new AtomicInteger();
        boolean started;
        boolean listenersRegisteredAtStart;

        @Override
        public AutoCloseable onNotification(Consumer<JsonObject> listener) {
            notifications.add(listener);
            return () -> {
                if (notifications.remove(listener)) notificationCloses.incrementAndGet();
            };
        }

        @Override
        public AutoCloseable onConnectionChange(Consumer<Boolean> listener) {
            connections.add(listener);
            return () -> {
                if (connections.remove(listener)) connectionCloses.incrementAndGet();
            };
        }

        @Override
        public void startMonitoring() {
            started = true;
            listenersRegisteredAtStart = !notifications.isEmpty() && !connections.isEmpty();
            connection(true);
        }

        void connection(boolean ready) {
            connections.forEach(listener -> listener.accept(ready));
        }

        void notifyWallet() {
            notifyWallet(null);
        }

        void notifyWallet(JsonArray params) {
            JsonObject notification = walletNotification(params);
            notifications.forEach(listener -> listener.accept(notification));
        }
    }
}