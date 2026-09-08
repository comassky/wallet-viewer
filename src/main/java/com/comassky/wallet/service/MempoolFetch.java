package com.comassky.wallet.service;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.codec.BodyCodec;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ServiceUnavailableException;

import java.time.Duration;
import java.util.function.Function;

/** Shared Vert.x-native mempool.space GET: parsed, shared in-flight, cached for a TTL, failures as 503. */
@ApplicationScoped
class MempoolFetch {
    private final WebClient webClient;

    @Inject
    MempoolFetch(Vertx vertx) {
        this.webClient = WebClient.create(vertx);
    }

    <T> Uni<T> cached(String url, Duration requestTimeout, Duration ttl,
                      Function<String, T> parse, String unavailableMessage) {
        // Cache outcomes (including failures) so a rate-limited or down provider is not hammered.
        return webClient.getAbs(url)
                .as(BodyCodec.string())
                .timeout(requestTimeout.toMillis())
                .send()
                .map(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException(unavailableMessage);
                    }
                    return parse.apply(response.body());
                })
                .onFailure().transform(failure -> new ServiceUnavailableException(unavailableMessage))
                .memoize().atLeast(ttl);
    }

    @PreDestroy
    void close() {
        webClient.close();
    }
}

