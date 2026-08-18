package com.example.walletviewer;

/** A receive address with its derivation path. */
public record ReceiveAddressDto(int index, String address, String path) {
}
