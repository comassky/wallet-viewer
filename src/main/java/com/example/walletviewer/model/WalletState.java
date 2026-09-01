package com.example.walletviewer.model;

/** Monotonic version within this process; last successful snapshot survives outages. */
public record WalletState(long version, String status, String message, WalletSnapshot snapshot) {
}