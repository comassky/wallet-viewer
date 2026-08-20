package com.example.walletviewer;

import io.smallrye.mutiny.Uni;
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
import java.time.Instant;

/** Public market data only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class PriceService {
    @ConfigProperty(name = "wallet.prices-url", defaultValue = "https://mempool.space/api/v1/prices")
    URI pricesUrl;

    private HttpClient client;
    private Uni<PriceRatesDto> cached;

    @PostConstruct
    void init() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(pricesUrl)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        // Share in-flight calls and cache outcomes briefly, including provider failures,
        // to avoid hammering a rate-limited or unavailable service.
        cached = Uni.createFrom().completionStage(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Price provider unavailable");
                    }
                    return parse(response.body());
                })
                .onFailure().transform(failure -> new ServiceUnavailableException("BTC price temporarily unavailable"))
                .memoize().atLeast(Duration.ofSeconds(60));
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

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}