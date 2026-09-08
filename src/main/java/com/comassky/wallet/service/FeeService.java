package com.comassky.wallet.service;

import com.comassky.wallet.model.FeeRatesDto;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** Public mempool fee estimates only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class FeeService {
    @ConfigProperty(name = "wallet.fees-url", defaultValue = "https://mempool.space/api/v1/fees/recommended")
    URI feesUrl;

    @Inject
    MempoolFetch fetch;

    private Uni<FeeRatesDto> cached;

    @PostConstruct
    void init() {
        cached = fetch.cached(feesUrl.toString(), Duration.ofSeconds(10), Duration.ofSeconds(60),
                FeeService::parse, "Fee estimates temporarily unavailable");
    }

    public Uni<FeeRatesDto> fees() {
        return cached;
    }

    static FeeRatesDto parse(String body) {
        JsonObject json = new JsonObject(body);
        long fastest = positive(json, "fastestFee");
        long halfHour = positive(json, "halfHourFee");
        long hour = positive(json, "hourFee");
        long economy = positive(json, "economyFee");
        long minimum = positive(json, "minimumFee");
        return new FeeRatesDto(fastest, halfHour, hour, economy, minimum, Instant.now().getEpochSecond());
    }

    private static long positive(JsonObject json, String key) {
        Long value = json.getLong(key);
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("Invalid fee estimate: " + key);
        }
        return value;
    }
}
