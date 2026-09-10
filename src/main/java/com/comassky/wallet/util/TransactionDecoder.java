package com.comassky.wallet.util;

import org.bitcoinj.core.Transaction;

import java.nio.ByteBuffer;
import java.util.HexFormat;

public final class TransactionDecoder {
    private TransactionDecoder() { }

    public static Transaction decode(String txid, String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalStateException("Invalid transaction response");
        }
        byte[] raw = HexFormat.of().parseHex(hex);
        Transaction transaction = Transaction.read(ByteBuffer.wrap(raw));
        if (transaction.messageSize() != raw.length || !transaction.getTxId().toString().equals(txid)) {
            throw new IllegalStateException("Invalid transaction response");
        }
        return transaction;
    }
}