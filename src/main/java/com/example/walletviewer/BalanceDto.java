package com.example.walletviewer;

/** Wallet balance in satoshis. */
public record BalanceDto(long confirmed, long unconfirmed, long total) {
}
