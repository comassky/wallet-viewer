package com.comassky.wallet.service;

import com.comassky.wallet.model.FeeRatesDto;
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

/** Public mempool fee estimates only: no wallet key, address or transaction is sent to the provider. */
@ApplicationScoped
public class FeeService {
    @ConfigProperty(name = "wallet.fees-url", defaultValue = "https://mempool.space/api/v1/fees/recommended")
    URI feesUrl;

    private HttpClient client;
    private Uni<FeeRatesDto> cached;

    @PostConstruct
    void init() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(feesUrl)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        // Share in-flight calls and cache outcomes briefly, including provider failures,
        // to avoid hammering a rate-limited or unavailable service.
        cached = Uni.createFrom().completionStage(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Fee provider unavailable");
                    }
                    return parse(response.body());
                })
                .onFailure().transform(failure -> new ServiceUnavailableException("Fee estimates temporarily unavailable"))
                .memoize().atLeast(Duration.ofSeconds(60));
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

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
