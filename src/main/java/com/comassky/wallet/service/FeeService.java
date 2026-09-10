package com.comassky.wallet.service;

import com.comassky.wallet.model.FeeRatesDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;
import java.time.Instant;

/** Public mempool fee estimates only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class FeeService {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final Duration TTL = Duration.ofSeconds(60);

    @Inject
    @RestClient
    MempoolClient client;

    private Uni<FeeRatesDto> cached;

    @PostConstruct
    void init() {
        // Cache outcomes (including failures) so a rate-limited or down provider is not hammered.
        cached = client.feesRecommended()
                .ifNoItem().after(REQUEST_TIMEOUT).fail()
                .map(FeeService::toFees)
                .onFailure().transform(failure -> new ServiceUnavailableException("Fee estimates temporarily unavailable"))
                .memoize().forFixedDuration(TTL);
    }

    public Uni<FeeRatesDto> fees() {
        return cached;
    }

    static FeeRatesDto toFees(MempoolClient.Fees raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Invalid fee estimate: empty");
        }
        final long fastest = positive(raw.fastestFee(), "fastestFee");
        final long halfHour = positive(raw.halfHourFee(), "halfHourFee");
        final long hour = positive(raw.hourFee(), "hourFee");
        final long economy = positive(raw.economyFee(), "economyFee");
        final long minimum = positive(raw.minimumFee(), "minimumFee");
        return new FeeRatesDto(fastest, halfHour, hour, economy, minimum, Instant.now().getEpochSecond());
    }

    private static long positive(Long value, String key) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("Invalid fee estimate: " + key);
        }
        return value;
    }
}
