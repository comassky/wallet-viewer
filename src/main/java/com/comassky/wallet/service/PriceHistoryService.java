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

/** Public historical BTC prices only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceHistoryService {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final Duration TTL = Duration.ofMinutes(30);

    @Inject
    @RestClient
    MempoolClient client;

    private Uni<List<PricePointDto>> cached;

    @PostConstruct
    void init() {
        // Historical prices move slowly, so cache for a while and share in-flight calls.
        cached = client.historicalPrice()
                .ifNoItem().after(REQUEST_TIMEOUT).fail()
                .map(PriceHistoryService::toPoints)
                .onFailure().transform(failure -> new ServiceUnavailableException("Price history temporarily unavailable"))
                .memoize().atLeast(TTL);
    }

    public Uni<List<PricePointDto>> history() {
        return cached;
    }

    static List<PricePointDto> toPoints(MempoolClient.History raw) {
        final List<MempoolClient.Price> prices = raw == null ? null : raw.prices();
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("Empty price history");
        }
        final List<PricePointDto> points = prices.stream()
                .filter(p -> p.time() != null && p.eur() != null && p.usd() != null && p.eur() > 0 && p.usd() > 0)
                .map(p -> new PricePointDto(p.time(), p.eur(), p.usd()))
                .sorted(Comparator.comparingLong(PricePointDto::time))
                .toList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No valid price points");
        }
        return points;
    }
}
