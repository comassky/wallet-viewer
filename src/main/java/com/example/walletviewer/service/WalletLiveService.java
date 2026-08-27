package com.example.walletviewer.service;

import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.TransactionDto;
import com.example.walletviewer.model.WalletSnapshot;
import com.example.walletviewer.model.WalletState;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Single writer: scan completely, replace Caffeine state, then notify subscribers. */
@ApplicationScoped
public class WalletLiveService {
    private static final Logger LOG = Logger.getLogger(WalletLiveService.class);
    private static final String KEY = "wallet";
    private final Cache<String, WalletState> cache = Caffeine.newBuilder().maximumSize(1).build();
    private final Set<Consumer<WalletState>> listeners = new HashSet<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wallet-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong changes = new AtomicLong();
    private final AtomicLong connectionEpoch = new AtomicLong();
    private volatile boolean connected;
    private volatile boolean stopped;
    private ScheduledFuture<?> scheduled;
    private AutoCloseable notifications;
    private AutoCloseable connections;
    private long version;

    @Inject WalletService scanner;
    @Inject ElectrumClient electrum;
    @Inject DemoService demo;

    public WalletLiveService() {
        cache.put(KEY, new WalletState(0, "loading", "Loading wallet from Electrum…", null));
    }

    void start(@Observes StartupEvent event) {
        if (demo.enabled()) {
            return; // DemoService seeds the cache and drives the synthetic wallet; skip Electrum.
        }
        notifications = electrum.onNotification(notification -> {
            changes.incrementAndGet();
            schedule(200);
            if (notification != null && "blockchain.scripthash.subscribe".equals(notification.getValue("method"))) {
                LOG.infof("Electrum address notification: address=%s", notificationAddress(notification));
            }
        });
        connections = electrum.onConnectionChange(ready -> {
            synchronized (this) {
                connected = ready;
                connectionEpoch.incrementAndGet();
                changes.incrementAndGet();
                if (ready) schedule(0);
                else publish("offline", "Electrum disconnected. Showing the last known wallet state.", null);
            }
        });
        electrum.startMonitoring();
    }

    private String notificationAddress(JsonObject notification) {
        // Stubs may omit params; malformed/untrusted values must never be printed or
        // learned. Bound the key before lookup, and never inspect or log the status text.
        if (!(notification.getValue("params") instanceof JsonArray params) || params.size() != 2
                || !(params.getValue(0) instanceof String hash) || hash.length() != 64
                || !hash.matches("[0-9a-fA-F]{64}")
                || (params.getValue(1) != null && !(params.getValue(1) instanceof String))) {
            return "unknown";
        }
        String address = scanner.knownAddressForScripthash(hash);
        return address == null ? "unknown" : address;
    }

    /** Replay and registration are atomic with respect to cache updates. */
    public synchronized AutoCloseable listen(Consumer<WalletState> listener) {
        listeners.add(listener);
        try { listener.accept(current()); }
        catch (RuntimeException failure) { listeners.remove(listener); throw failure; }
        return () -> { synchronized (this) { listeners.remove(listener); } };
    }

    public WalletState current() { return cache.getIfPresent(KEY); }

    public Uni<WalletSnapshot> snapshot() {
        return Uni.createFrom().deferred(() -> {
            WalletState state = current();
            if (state.snapshot() != null) return Uni.createFrom().item(state.snapshot());
            return Uni.createFrom().failure(new ServiceUnavailableException("Wallet is still synchronizing"));
        });
    }

    private synchronized void schedule(long delayMillis) {
        if (stopped || !connected || (scheduled != null && !scheduled.isDone())) return;
        scheduled = worker.schedule(this::synchronizeWallet, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void synchronizeWallet() {
        long epoch = connectionEpoch.get();
        long revision = changes.get();
        boolean failed = false;
        long started = System.nanoTime();
        boolean scanStarted = false;
        String outcome = "discarded";
        Long newTransactions = null;
        try {
            if (stopped || !connected) return;
            scanStarted = true;
            LOG.info("Wallet scan started");
            publish("syncing", "Synchronizing wallet with Electrum…", null);
            // Off the Vert.x event loop; only one scan can run at any time.
            WalletSnapshot snapshot = scanner.scan().await().atMost(Duration.ofMinutes(5));
            synchronized (this) {
                if (stopped || !connected || epoch != connectionEpoch.get()) return;
                // A notification during the scan can make its individual RPCs inconsistent.
                // Keep the previous complete snapshot and run another pass before publishing.
                if (revision != changes.get()) return;
                WalletSnapshot previous = current().snapshot();
                if (previous != null) {
                    Set<String> known = new HashSet<>();
                    previous.transactions().forEach(tx -> known.add(tx.txid()));
                    newTransactions = snapshot.transactions().stream().map(TransactionDto::txid)
                            .distinct().filter(id -> !known.contains(id)).count();
                }
                publish("live", null, snapshot);
                outcome = "success";
            }
        } catch (RuntimeException failure) {
            failed = true;
            outcome = "failure";
            if (!stopped) LOG.warnf("Wallet sync failure: type=%s", failure.getClass().getSimpleName());
            if (connected && !stopped) publish("error", "Wallet synchronization failed. Retrying automatically.", null);
        } finally {
            if (scanStarted) {
                long durationMillis = (System.nanoTime() - started) / 1_000_000;
                if (newTransactions == null) {
                    LOG.infof("Wallet scan completed: durationMs=%d outcome=%s", durationMillis, outcome);
                } else {
                    LOG.infof("Wallet scan completed: durationMs=%d outcome=%s newTransactions=%d",
                            durationMillis, outcome, newTransactions);
                }
            }
            synchronized (this) {
                scheduled = null;
                if (!stopped && connected && (failed || revision != changes.get() || epoch != connectionEpoch.get())) {
                    schedule(failed ? 5000 : 200);
                }
            }
        }
    }

    // Package-private so DemoService can push synthetic snapshots into the same cache.
    synchronized void publish(String status, String message, WalletSnapshot snapshot) {
        if (stopped) return;
        WalletState previous = current();
        WalletState next = new WalletState(++version, status, message,
                snapshot != null ? snapshot : previous.snapshot());
        cache.put(KEY, next);
        for (Consumer<WalletState> listener : Set.copyOf(listeners)) {
            try { listener.accept(next); }
            catch (RuntimeException ignored) { listeners.remove(listener); }
        }
    }

    @PreDestroy
    void stop() {
        stopped = true;
        closeRegistration(notifications);
        closeRegistration(connections);
        worker.shutdownNow();
        synchronized (this) { listeners.clear(); }
    }

    private static void closeRegistration(AutoCloseable registration) {
        if (registration != null) try { registration.close(); } catch (Exception ignored) { }
    }
}