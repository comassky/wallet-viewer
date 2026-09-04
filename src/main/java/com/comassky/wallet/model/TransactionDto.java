package com.comassky.wallet.model;

import java.util.List;

/**
 * A wallet transaction with its net effect (all amounts in satoshis).
 * type is "received", "sent" or "self".
 * addresses lists the wallet-owned addresses involved (credited or debited) in this transaction.
 */
public record TransactionDto(String txid, long amount, long received, long sent,
                             int height, int confirmations, Long timestamp, String type,
                             List<String> addresses) {
    public TransactionDto {
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }

    public TransactionDto(String txid, long amount, long received, long sent,
                          int height, int confirmations, Long timestamp, String type) {
        this(txid, amount, received, sent, height, confirmations, timestamp, type, List.of());
    }
}
