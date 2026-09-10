package com.comassky.wallet.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Monotonic version within this process; last successful snapshot survives outages. */
// Only serialized through the WebSocket's ObjectMapper, so not auto-registered for native.
@RegisterForReflection
public record WalletState(long version, WalletStatus status, String message, WalletSnapshot snapshot) {
}