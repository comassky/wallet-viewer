package com.comassky.wallet.derivation;

import com.comassky.wallet.model.AddressInfo;
import jakarta.enterprise.context.ApplicationScoped;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.internal.CryptoUtils;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bouncycastle.math.ec.ECPoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives receive/change addresses from an account-level extended public key
 * (zpub / ypub / xpub) and computes the Electrum "scripthash" for each.
 * Supports legacy (BIP44), wrapped segwit (BIP49), native segwit (BIP84) and
 * Taproot (BIP86).
 */
@ApplicationScoped
public class HdWallet {

    private static final Logger LOG = Logger.getLogger(HdWallet.class);

    enum ScriptType {
        P2PKH(44), P2SH_P2WPKH(49), P2WPKH(84), P2TR(86);

        final int purpose;

        ScriptType(int purpose) {
            this.purpose = purpose;
        }
    }

    // xpub mainnet version bytes (0x0488B21E)
    private static final byte[] XPUB_VERSION = {0x04, (byte) 0x88, (byte) 0xB2, 0x1E};

    @ConfigProperty(name = "wallet.xpub")
    String extPub;
    @ConfigProperty(name = "wallet.network", defaultValue = "mainnet")
    String network;
    // auto | p2pkh | p2sh-p2wpkh | p2wpkh | p2tr. Needed for Taproot since BIP86 keys are plain xpubs.
    @ConfigProperty(name = "wallet.script-type", defaultValue = "auto")
    String scriptTypeCfg;

    private volatile boolean initialized = false;
    private NetworkParameters params;
    private DeterministicKey account;
    private ScriptType scriptType;
    private String basePath;
    private final Map<Integer, DeterministicKey> chainKeys = new ConcurrentHashMap<>();

    private synchronized void ensureInit() {
        if (initialized) {
            return;
        }
        params = "testnet".equalsIgnoreCase(network) ? TestNet3Params.get() : MainNetParams.get();
        String p = extPub.trim();
        String prefix = p.substring(0, 4).toLowerCase();
        boolean segwitPrefix = prefix.equals("zpub") || prefix.equals("vpub")
                || prefix.equals("ypub") || prefix.equals("upub");
        String xpub = segwitPrefix ? toXpub(p) : p;

        String override = scriptTypeCfg == null ? "auto" : scriptTypeCfg.trim().toLowerCase();
        scriptType = resolveScriptType(prefix, override);
        basePath = "m/" + scriptType.purpose + "'/0'/0'";
        if ("auto".equals(override)) {
            LOG.infof("Wallet script type auto-detected: %s (BIP%d, %s) from %s key",
                    scriptType, scriptType.purpose, basePath, prefix);
        } else {
            LOG.infof("Wallet script type configured: %s (BIP%d, %s)",
                    scriptType, scriptType.purpose, basePath);
        }
        account = DeterministicKey.deserializeB58(null, xpub, params);
        initialized = true;
    }

    static ScriptType resolveScriptType(String prefix, String override) {
        return switch (override) {
            case "auto" -> switch (prefix) {
                case "zpub", "vpub" -> ScriptType.P2WPKH;
                case "ypub", "upub" -> ScriptType.P2SH_P2WPKH;
                default -> ScriptType.P2PKH;
            };
            case "p2tr", "taproot" -> ScriptType.P2TR;
            case "p2wpkh", "bech32" -> ScriptType.P2WPKH;
            case "p2sh-p2wpkh", "p2sh" -> ScriptType.P2SH_P2WPKH;
            default -> ScriptType.P2PKH;
        };
    }

    /** Re-encodes a zpub/ypub as an xpub so bitcoinj can parse it (only the version bytes differ). */
    private static String toXpub(String ext) {
        byte[] data = Base58.decodeChecked(ext); // version(4) + payload, checksum stripped
        byte[] out = data.clone();
        System.arraycopy(XPUB_VERSION, 0, out, 0, 4);
        byte[] check = Sha256Hash.hashTwice(out);
        byte[] full = new byte[out.length + 4];
        System.arraycopy(out, 0, full, 0, out.length);
        System.arraycopy(check, 0, full, out.length, 4);
        return Base58.encode(full);
    }

    public NetworkParameters params() {
        ensureInit();
        return params;
    }

    /**
     * @param chain 0 = external/receive, 1 = internal/change
     * @param index address index
     */
    public AddressInfo address(int chain, int index) {
        if ((chain != 0 && chain != 1) || index < 0) {
            throw new IllegalArgumentException("Expected chain 0 or 1 and a non-negative address index");
        }
        ensureInit();
        DeterministicKey chainKey = chainKeys.computeIfAbsent(chain,
                c -> HDKeyDerivation.deriveChildKey(account, new ChildNumber(c, false)));
        DeterministicKey key = HDKeyDerivation.deriveChildKey(chainKey, new ChildNumber(index, false));

        Address addr = switch (scriptType) {
            case P2WPKH -> SegwitAddress.fromKey(params, key);
            case P2TR -> taprootAddress(key);
            case P2SH_P2WPKH -> {
                Script redeem = ScriptBuilder.createP2WPKHOutputScript(key.getPubKeyHash());
                yield LegacyAddress.fromScriptHash(params, CryptoUtils.sha256hash160(redeem.getProgram()));
            }
            case P2PKH -> LegacyAddress.fromKey(params, key);
        };

        byte[] program = ScriptBuilder.createOutputScript(addr).getProgram();
        String scripthash = ByteUtils.formatHex(ByteUtils.reverseBytes(Sha256Hash.hash(program)));
        return new AddressInfo(chain, index, addr.toString(), scripthash, ByteUtils.formatHex(program),
                basePath + "/" + chain + "/" + index);
    }

    /** BIP86 key-path-only Taproot: tweak the derived internal key and encode as bech32m (bc1p...). */
    private Address taprootAddress(DeterministicKey key) {
        ECPoint point = key.getPubKeyPoint().normalize();
        // The x-only internal key implicitly has an even Y coordinate (BIP340/341).
        ECPoint internal = point.getAffineYCoord().toBigInteger().testBit(0) ? point.negate().normalize() : point;
        byte[] internalX = to32(internal.getAffineXCoord().toBigInteger());
        BigInteger tweak = new BigInteger(1, taggedHash("TapTweak", internalX));
        ECPoint output = ECKey.ecDomainParameters().getG().multiply(tweak).add(internal).normalize();
        byte[] outputX = to32(output.getAffineXCoord().toBigInteger());
        return SegwitAddress.fromProgram(params, 1, outputX);
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
