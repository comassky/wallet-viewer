package com.comassky.wallet.service;

import com.comassky.wallet.config.WalletConfig;
import com.comassky.wallet.model.TransactionDetailsDto;
import com.comassky.wallet.model.WalletSnapshot;
import com.comassky.wallet.model.WalletStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DemoService {
    private static final Logger LOG = Logger.getLogger(DemoService.class);

    @Inject DemoWallet simulation;
    @Inject WalletLiveService live;
    boolean demo;
    private ScheduledExecutorService worker;
    private volatile boolean stopped;

    @Inject
    void configure(WalletConfig config) {
        demo = config.demo();
    }

    public boolean enabled() {
        return demo;
    }

    void start(@Observes StartupEvent event) {
        if (!demo) return;
        live.publish(WalletStatus.LIVE, "Demo mode \u2014 synthetic data.", snapshot());
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "demo-wallet");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleAtFixedRate(this::emit, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        stopped = true;
        if (worker != null) worker.shutdownNow();
    }

    private void emit() {
        if (stopped) return;
        try {
            live.publish(WalletStatus.LIVE, null, simulation.tick());
        } catch (RuntimeException failure) {
            LOG.warnf("Demo update failed: type=%s", failure.getClass().getSimpleName());
        }
    }

    public WalletSnapshot snapshot() {
        return simulation.snapshot();
    }

    public TransactionDetailsDto details(String txid) {
        return simulation.details(txid);
    }
}