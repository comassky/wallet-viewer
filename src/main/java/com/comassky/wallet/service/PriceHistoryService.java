package com.comassky.wallet.service;

import com.comassky.wallet.model.PricePointDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;

/** Public historical BTC prices only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceHistoryService {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final Duration TTL = Duration.ofMinutes(30);
    static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    LongSupplier nanoTime = System::nanoTime;
    private volatile long reuseUntil;
    private volatile List<PricePointDto> lastGood;
    private volatile boolean stale;

    @Inject
    @RestClient
    MempoolClient client;

    private Uni<List<PricePointDto>> cached;

    @PostConstruct
    void init() {
        cached = Uni.createFrom().deferred(() -> client.historicalPrice())
                .ifNoItem().after(REQUEST_TIMEOUT).fail()
                .map(PriceHistoryService::toPoints)
            .invoke(points -> lastGood = points)
            .onItemOrFailure().invoke((points, failure) -> {
                stale = failure != null;
                reuseUntil = nanoTime.getAsLong() + (stale ? RETRY_DELAY : TTL).toNanos();
            })
                .onFailure().transform(failure -> new ServiceUnavailableException("Price history temporarily unavailable"))
            .onFailure().recoverWithUni(failure -> lastGood == null
                ? Uni.createFrom().failure(failure) : Uni.createFrom().item(lastGood))
            .memoize().until(() -> nanoTime.getAsLong() - reuseUntil >= 0);
    }

    public Uni<List<PricePointDto>> history() {
        return cached;
    }

    public boolean stale() {
        return stale;
    }

    static List<PricePointDto> toPoints(MempoolClient.History raw) {
        final List<MempoolClient.Price> prices = raw == null ? null : raw.prices();
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("Empty price history");
        }
        final List<PricePointDto> points = prices.stream()
                .filter(p -> p.time() != null && p.time() >= 0 && p.eur() != null && p.usd() != null
                    && Double.isFinite(p.eur()) && Double.isFinite(p.usd()) && p.eur() > 0 && p.usd() > 0)
                .map(p -> new PricePointDto(p.time(), p.eur(), p.usd()))
                .sorted(Comparator.comparingLong(PricePointDto::time))
                .toList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No valid price points");
        }
        return points;
    }
}
