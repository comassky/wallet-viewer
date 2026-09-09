package com.comassky.wallet.service;

import com.comassky.wallet.model.PricePointDto;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public historical BTC prices only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceHistoryService {
    @ConfigProperty(name = "wallet.price-history-url", defaultValue = "https://mempool.space/api/v1/historical-price")
    URI historyUrl;

    @Inject
    MempoolFetch fetch;

    private Uni<List<PricePointDto>> cached;

    @PostConstruct
    void init() {
        // Historical prices move slowly, so cache for a while and share in-flight calls.
        cached = fetch.cached(historyUrl.toString(), Duration.ofSeconds(15), Duration.ofMinutes(30),
                PriceHistoryService::parse, "Price history temporarily unavailable");
    }

    public Uni<List<PricePointDto>> history() {
        return cached;
    }

    static List<PricePointDto> parse(String body) {
        JsonArray prices = new JsonObject(body).getJsonArray("prices");
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("Empty price history");
        }
        List<PricePointDto> points = new ArrayList<>(prices.size());
        for (int i = 0; i < prices.size(); i++) {
            JsonObject point = prices.getJsonObject(i);
            Long time = point.getLong("time");
            Double eur = point.getDouble("EUR");
            Double usd = point.getDouble("USD");
            if (time == null || eur == null || usd == null || eur <= 0 || usd <= 0) {
                continue;
            }
            points.add(new PricePointDto(time, eur, usd));
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No valid price points");
        }
        points.sort(Comparator.comparingLong(PricePointDto::time));
        return points;
    }
}
