package com.comassky.wallet.model;

/** A historical BTC price point: provider timestamp (Unix seconds) with EUR and USD quotes. */
public record PricePointDto(long time, double eur, double usd) {
}
