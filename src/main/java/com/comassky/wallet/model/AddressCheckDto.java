package com.comassky.wallet.model;

/** Result of checking whether an address is derived from the configured extended public key. */
public record AddressCheckDto(String address, boolean belongs, Integer chain, Integer index, String path, int checked) {
}
