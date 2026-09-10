package com.comassky.wallet.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Net effect of a transaction on the wallet; the JSON wire value stays lowercase. */
public enum TransactionType {
    RECEIVED("received"),
    SENT("sent"),
    SELF("self");

    private final String wire;

    TransactionType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
