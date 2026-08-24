package com.example.walletviewer.model;

/** Fiat units per BTC, with the provider's quote timestamp (Unix seconds). */
public record PriceRatesDto(double eur, double usd, long timestamp) {
}