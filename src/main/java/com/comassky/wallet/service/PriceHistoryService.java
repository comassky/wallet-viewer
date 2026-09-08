package com.comassky.wallet.service;

import com.comassky.wallet.model.PricePointDto;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public historical BTC prices only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceHistoryService {
    @ConfigProperty(name = "wallet.price-history-url", defaultValue = "https://mempool.space/api/v1/historical-price")
    URI historyUrl;

    private HttpClient client;
    private Uni<List<PricePointDto>> cached;

    @PostConstruct
    void init() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(historyUrl)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET().build();
        // Historical prices move slowly, so cache for a while and share in-flight calls.
        cached = Uni.createFrom().completionStage(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Price history unavailable");
                    }
                    return parse(response.body());
                })
                .onFailure().transform(failure -> new ServiceUnavailableException("Price history temporarily unavailable"))
                .memoize().atLeast(Duration.ofMinutes(30));
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

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
