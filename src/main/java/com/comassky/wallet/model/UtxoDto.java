package com.comassky.wallet.model;

/** A single unspent transaction output (value in satoshis). */
public record UtxoDto(String txid, int vout, long value, int height, int confirmations, String address) {
}
