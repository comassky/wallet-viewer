package com.comassky.wallet.derivation;

import org.junit.jupiter.api.Test;

import static com.comassky.wallet.derivation.HdWallet.ScriptType.P2PKH;
import static com.comassky.wallet.derivation.HdWallet.ScriptType.P2SH_P2WPKH;
import static com.comassky.wallet.derivation.HdWallet.ScriptType.P2TR;
import static com.comassky.wallet.derivation.HdWallet.ScriptType.P2WPKH;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HdWalletTest {
    @Test
    void infersScriptTypeFromExtendedPublicKeyPrefix() {
        assertEquals(P2PKH, HdWallet.resolveScriptType("xpub", "auto"));
        assertEquals(P2PKH, HdWallet.resolveScriptType("tpub", "auto"));
        for (String prefix : new String[]{"zpub", "vpub"}) {
            assertEquals(P2WPKH, HdWallet.resolveScriptType(prefix, "auto"));
        }
        for (String prefix : new String[]{"ypub", "upub"}) {
            assertEquals(P2SH_P2WPKH, HdWallet.resolveScriptType(prefix, "auto"));
        }
    }

    @Test
    void explicitTypesAndAliasesOverrideThePrefix() {
        for (String prefix : new String[]{"xpub", "zpub", "ypub"}) {
            for (String override : new String[]{"p2tr", "taproot"}) {
                assertEquals(P2TR, HdWallet.resolveScriptType(prefix, override));
            }
            for (String override : new String[]{"p2wpkh", "bech32"}) {
                assertEquals(P2WPKH, HdWallet.resolveScriptType(prefix, override));
            }
            for (String override : new String[]{"p2sh-p2wpkh", "p2sh"}) {
                assertEquals(P2SH_P2WPKH, HdWallet.resolveScriptType(prefix, override));
            }
            // Preserve the existing legacy fallback, including unknown overrides.
            assertEquals(P2PKH, HdWallet.resolveScriptType(prefix, "p2pkh"));
            assertEquals(P2PKH, HdWallet.resolveScriptType(prefix, "unknown"));
        }
    }

    @Test
    void scriptTypesCarryTheirBipPurpose() {
        assertEquals(44, P2PKH.purpose);
        assertEquals(49, P2SH_P2WPKH.purpose);
        assertEquals(84, P2WPKH.purpose);
        assertEquals(86, P2TR.purpose);
    }
}