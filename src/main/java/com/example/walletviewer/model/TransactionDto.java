package com.example.walletviewer.model;

/**
 * A wallet transaction with its net effect (all amounts in satoshis).
 * type is "received", "sent" or "self".
 */
public record TransactionDto(String txid, long amount, long received, long sent,
                             int height, int confirmations, Long timestamp, String type) {
}
