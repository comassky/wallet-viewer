package com.comassky.wallet.service;

import com.comassky.wallet.model.PriceRatesDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;
import java.time.Instant;

/** Public market data only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceService {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TTL = Duration.ofSeconds(60);

    @Inject
    @RestClient
    MempoolClient client;

    private Uni<PriceRatesDto> cached;

    @PostConstruct
    void init() {
        // Cache outcomes (including failures) so a rate-limited or down provider is not hammered.
        cached = client.prices()
                .ifNoItem().after(REQUEST_TIMEOUT).fail()
                .map(PriceService::toRates)
                .onFailure().transform(failure -> new ServiceUnavailableException("BTC price temporarily unavailable"))
                .memoize().atLeast(TTL);
    }

    public Uni<PriceRatesDto> rates() {
        return cached;
    }

    static PriceRatesDto toRates(MempoolClient.Price raw) {
        final Double eur = raw == null ? null : raw.eur();
        final Double usd = raw == null ? null : raw.usd();
        final Long timestamp = raw == null ? null : raw.time();
        final long now = Instant.now().getEpochSecond();
        if (eur == null || usd == null || !Double.isFinite(eur) || !Double.isFinite(usd)
                || eur <= 0 || usd <= 0 || timestamp == null || timestamp < now - 900 || timestamp > now + 300) {
            throw new IllegalArgumentException("Invalid or outdated BTC quote");
        }
        return new PriceRatesDto(eur, usd, timestamp);
    }
}