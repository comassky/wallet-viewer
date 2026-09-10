package com.comassky.wallet.electrum;

import java.util.Arrays;

/** Allowlisted Electrum JSON-RPC methods; the single source of truth for wire method names. */
public enum ElectrumMethod {
    SERVER_VERSION("server.version"),
    SERVER_PING("server.ping"),
    HEADERS_SUBSCRIBE("blockchain.headers.subscribe"),
    SCRIPTHASH_SUBSCRIBE("blockchain.scripthash.subscribe"),
    SCRIPTHASH_GET_HISTORY("blockchain.scripthash.get_history"),
    SCRIPTHASH_GET_BALANCE("blockchain.scripthash.get_balance"),
    SCRIPTHASH_LISTUNSPENT("blockchain.scripthash.listunspent"),
    BLOCK_HEADER("blockchain.block.header"),
    TRANSACTION_GET("blockchain.transaction.get");

    private final String wire;

    ElectrumMethod(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** True when the wire name matches one of the allowlisted methods (never trust caller/peer input). */
    public static boolean isKnown(String wire) {
        return Arrays.stream(values()).anyMatch(method -> method.wire.equals(wire));
    }
}
