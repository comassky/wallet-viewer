package com.comassky.wallet.derivation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Validates the Taproot (BIP86) tweak against the official BIP86 test vectors. */
class TaprootDerivationTest {

    private static final String ACCOUNT_XPUB =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ";

    @Test
    void bip86Vectors() {
        HdWallet wallet = wallet();

        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
                wallet.address(0, 0).address());
        assertEquals("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh",
                wallet.address(0, 1).address());
        assertEquals("bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7",
                wallet.address(1, 0).address());
        assertEquals("m/86'/0'/0'/0/0", wallet.address(0, 0).path());
    }

    @Test
    void rejectsInvalidDerivationIndices() {
        HdWallet wallet = wallet();
        assertThrows(IllegalArgumentException.class, () -> wallet.address(0, -1));
        assertThrows(IllegalArgumentException.class, () -> wallet.address(2, 0));
    }

    private static HdWallet wallet() {
        HdWallet wallet = new HdWallet();
        wallet.extPub = ACCOUNT_XPUB;
        wallet.network = "mainnet";
        wallet.scriptTypeCfg = "p2tr";
        return wallet;
    }
}
