package com.example.walletviewer;

import io.vertx.core.json.JsonArray;

/** Internal holder for a derived address and its scan state. */
public class AddressInfo {
    public final int chain;
    public final int index;
    public final String address;
    public final String scripthash;
    public final String scriptHex;
    public final String path;
    public JsonArray history;

    public AddressInfo(int chain, int index, String address, String scripthash, String scriptHex, String path) {
        this.chain = chain;
        this.index = index;
        this.address = address;
        this.scripthash = scripthash;
        this.scriptHex = scriptHex;
        this.path = path;
    }

    public boolean used() {
        return history != null && !history.isEmpty();
    }
}
