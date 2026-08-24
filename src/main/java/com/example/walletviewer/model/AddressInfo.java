package com.example.walletviewer.model;

/** Immutable local derivation data; Electrum scan state is kept separately. */
public final class AddressInfo {
    public final int chain;
    public final int index;
    public final String address;
    public final String scripthash;
    public final String scriptHex;
    public final String path;

    public AddressInfo(int chain, int index, String address, String scripthash, String scriptHex, String path) {
        this.chain = chain;
        this.index = index;
        this.address = address;
        this.scripthash = scripthash;
        this.scriptHex = scriptHex;
        this.path = path;
    }
}
