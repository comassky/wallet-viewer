package com.example.walletviewer.model;

import java.util.List;

/** On-demand transaction details; all amounts are in satoshis, not wallet net amounts. */
public record TransactionDetailsDto(String txid, long version, long lockTime, int size,
                                    List<Input> inputs, List<Output> outputs,
                                    Long totalInput, long totalOutput, Long fee) {
    public TransactionDetailsDto {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    public record Input(String txid, long vout, String address, Long value, boolean coinbase) { }

    public record Output(int index, String address, long value, String scriptHex) { }
}