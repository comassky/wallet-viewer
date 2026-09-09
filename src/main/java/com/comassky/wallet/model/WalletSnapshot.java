package com.comassky.wallet.model;

import java.util.List;

/** Full wallet snapshot returned by the API. */
public record WalletSnapshot(BalanceDto balance,
                             List<UtxoDto> utxos,
                             List<TransactionDto> transactions,
                             ReceiveAddressDto receiveAddress) {
    public WalletSnapshot {
        utxos = List.copyOf(utxos);
        transactions = List.copyOf(transactions);
    }
}
