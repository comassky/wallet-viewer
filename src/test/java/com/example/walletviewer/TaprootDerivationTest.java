package com.example.walletviewer;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Validates the Taproot (BIP86) tweak against the official BIP86 test vectors. */
class TaprootDerivationTest {

    private static final String ACCOUNT_XPUB =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ";

    @Test
    void bip86Vectors() {
        NetworkParameters params = MainNetParams.get();
        DeterministicKey account = DeterministicKey.deserializeB58(null, ACCOUNT_XPUB, params);

        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
                taproot(params, account, 0, 0));
        assertEquals("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh",
                taproot(params, account, 0, 1));
        assertEquals("bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7",
                taproot(params, account, 1, 0));
    }

    private static String taproot(NetworkParameters params, DeterministicKey account, int chain, int index) {
        DeterministicKey chainKey = HDKeyDerivation.deriveChildKey(account, new ChildNumber(chain, false));
        DeterministicKey key = HDKeyDerivation.deriveChildKey(chainKey, new ChildNumber(index, false));

        ECPoint point = key.getPubKeyPoint().normalize();
        ECPoint internal = point.getAffineYCoord().toBigInteger().testBit(0) ? point.negate().normalize() : point;
        byte[] internalX = to32(internal.getAffineXCoord().toBigInteger());
        BigInteger tweak = new BigInteger(1, taggedHash("TapTweak", internalX));
        ECPoint output = ECKey.CURVE.getG().multiply(tweak).add(internal).normalize();
        byte[] outputX = to32(output.getAffineXCoord().toBigInteger());
        Address addr = SegwitAddress.fromProgram(params, 1, outputX);
        return addr.toString();
    }

    private static byte[] taggedHash(String tag, byte[] msg) {
        byte[] t = Sha256Hash.hash(tag.getBytes(StandardCharsets.UTF_8));
        byte[] data = new byte[t.length * 2 + msg.length];
        System.arraycopy(t, 0, data, 0, t.length);
        System.arraycopy(t, 0, data, t.length, t.length);
        System.arraycopy(msg, 0, data, t.length * 2, msg.length);
        return Sha256Hash.hash(data);
    }

    private static byte[] to32(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length == 32) {
            return b;
        }
        byte[] out = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }
}
