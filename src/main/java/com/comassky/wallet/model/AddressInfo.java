package com.comassky.wallet.model;

/** Immutable local derivation data; Electrum scan state is kept separately. */
public record AddressInfo(int chain, int index, String address, String scripthash, String scriptHex, String path) { }
