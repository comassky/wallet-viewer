package com.comassky.wallet.util;

import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.Transaction;

import java.nio.ByteBuffer;

public final class TransactionDecoder {
    private TransactionDecoder() { }

    public static Transaction decode(String txid, String hex) {
        if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalStateException("Invalid transaction response");
        }
        byte[] raw = ByteUtils.parseHex(hex);
        Transaction transaction = Transaction.read(ByteBuffer.wrap(raw));
        if (transaction.messageSize() != raw.length || !transaction.getTxId().toString().equals(txid)) {
            throw new IllegalStateException("Invalid transaction response");
        }
        return transaction;
    }
}