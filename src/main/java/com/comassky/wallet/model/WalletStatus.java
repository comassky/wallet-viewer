package com.comassky.wallet.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle state of the wallet stream; the JSON wire value stays lowercase. */
public enum WalletStatus {
    LOADING("loading"),
    SYNCING("syncing"),
    LIVE("live"),
    OFFLINE("offline"),
    ERROR("error");

    private final String wire;

    WalletStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
