package com.comassky.wallet.model;

/** One day of wallet history: cumulative balance (sats) and its fiat value at that day's price. */
public record BalancePointDto(long time, long balanceSats, double valueEur, double valueUsd) {
}
