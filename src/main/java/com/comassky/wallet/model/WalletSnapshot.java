package com.comassky.wallet.model;

import java.util.List;

/** Full wallet snapshot returned by the API. */
public record WalletSnapshot(BalanceDto balance,
                             List<UtxoDto> utxos,
                             List<TransactionDto> transactions,
                             ReceiveAddressDto receiveAddress,
                             Discovery discovery) {
    public record Discovery(boolean complete, int receiveScanned, int changeScanned, int addressLimit, int gapLimit) { }

    public WalletSnapshot(BalanceDto balance, List<UtxoDto> utxos, List<TransactionDto> transactions,
                          ReceiveAddressDto receiveAddress) {
        this(balance, utxos, transactions, receiveAddress, null);
    }

    public WalletSnapshot {
        utxos = List.copyOf(utxos);
        transactions = List.copyOf(transactions);
    }
}
