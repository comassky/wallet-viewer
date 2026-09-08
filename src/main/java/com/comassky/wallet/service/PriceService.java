package com.comassky.wallet.service;

import com.comassky.wallet.model.PriceRatesDto;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** Public market data only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceService {
    @ConfigProperty(name = "wallet.prices-url", defaultValue = "https://mempool.space/api/v1/prices")
    URI pricesUrl;

    @Inject
    MempoolFetch fetch;

    private Uni<PriceRatesDto> cached;

    @PostConstruct
    void init() {
        cached = fetch.cached(pricesUrl.toString(), Duration.ofSeconds(10), Duration.ofSeconds(60),
                PriceService::parse, "BTC price temporarily unavailable");
    }

    public Uni<PriceRatesDto> rates() {
        return cached;
    }

    static PriceRatesDto parse(String body) {
        JsonObject json = new JsonObject(body);
        Double eur = json.getDouble("EUR");
        Double usd = json.getDouble("USD");
        Long timestamp = json.getLong("time");
        long now = Instant.now().getEpochSecond();
        if (eur == null || usd == null || !Double.isFinite(eur) || !Double.isFinite(usd)
                || eur <= 0 || usd <= 0 || timestamp == null || timestamp < now - 900 || timestamp > now + 300) {
            throw new IllegalArgumentException("Invalid or outdated BTC quote");
        }
        return new PriceRatesDto(eur, usd, timestamp);
    }
}