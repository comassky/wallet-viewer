package com.comassky.wallet.service;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

/**
 * Supplies a bundled public test key (and Taproot script type) as {@code wallet.xpub} defaults when
 * demo mode is on (WALLET_DEMO=true), so the app derives valid demo addresses without a configured
 * wallet.xpub. Keeps demo knowledge out of {@code HdWallet}. Its ordinal (275) sits above
 * application.properties (250) but below environment variables (300), so an explicit WALLET_XPUB /
 * WALLET_SCRIPT_TYPE still wins.
 */
public final class DemoConfigSource implements ConfigSource {
    // Public BIP86 test-vector account key (bech32m Taproot addresses).
    private static final String DEMO_XPUB =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ";

    private final boolean demo = "true".equalsIgnoreCase(System.getenv("WALLET_DEMO"))
            || Boolean.getBoolean("wallet.demo");
    private final Map<String, String> values = Map.of(
            "wallet.xpub", DEMO_XPUB,
            "wallet.script-type", "p2tr");

    @Override
    public Set<String> getPropertyNames() {
        return demo ? values.keySet() : Set.of();
    }

    @Override
    public String getValue(String propertyName) {
        return demo ? values.get(propertyName) : null;
    }

    @Override
    public String getName() {
        return "wallet-demo";
    }

    @Override
    public int getOrdinal() {
        return 275;
    }
}
