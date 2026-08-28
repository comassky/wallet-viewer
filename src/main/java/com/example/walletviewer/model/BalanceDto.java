package com.example.walletviewer.model;

/** Wallet balance in satoshis. */
public record BalanceDto(long confirmed, long unconfirmed, long total) {
	public BalanceDto(long confirmed, long unconfirmed) {
		this(confirmed, unconfirmed, confirmed + unconfirmed);
	}
}
