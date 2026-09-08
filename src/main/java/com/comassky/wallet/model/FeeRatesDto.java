package com.comassky.wallet.model;

/** Recommended network fee rates in sat/vB, with the server fetch timestamp (Unix seconds). */
public record FeeRatesDto(long fastest, long halfHour, long hour, long economy, long minimum, long timestamp) {
}
