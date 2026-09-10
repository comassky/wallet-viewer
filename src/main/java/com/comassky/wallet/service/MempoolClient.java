package com.comassky.wallet.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/** Declarative reactive client for mempool.space public endpoints; no wallet data is ever sent. */
@RegisterRestClient(configKey = "mempool", baseUri = "https://mempool.space")
@Produces(MediaType.APPLICATION_JSON)
public interface MempoolClient {

    @GET
    @Path("/api/v1/prices")
    Uni<Price> prices();

    @GET
    @Path("/api/v1/fees/recommended")
    Uni<Fees> feesRecommended();

    @GET
    @Path("/api/v1/historical-price")
    Uni<History> historicalPrice();

    /** Lenient provider quote; validated into the domain DTO by the service. */
    record Price(@JsonProperty("EUR") Double eur, @JsonProperty("USD") Double usd,
                 @JsonProperty("time") Long time) {
    }

    record Fees(Long fastestFee, Long halfHourFee, Long hourFee, Long economyFee, Long minimumFee) {
    }

    record History(List<Price> prices) {
    }
}
