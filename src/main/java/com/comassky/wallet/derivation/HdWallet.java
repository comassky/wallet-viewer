package com.comassky.wallet.derivation;

import com.comassky.wallet.model.AddressInfo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    // Derivation is deterministic for a fixed key, so memoize the immutable result by (chain, index).
    private final Cache<Long, AddressInfo> addressCache = Caffeine.newBuilder().maximumSize(20_000).build();

    private synchronized void ensureInit() {
        if (initialized) {
            return;
        }
        params = "testnet".equalsIgnoreCase(network) ? TestNet3Params.get() : MainNetParams.get();
        final String p = extPub.trim();
        final String prefix = p.substring(0, 4).toLowerCase();
        final boolean segwitPrefix = prefix.equals("zpub") || prefix.equals("vpub")
                || prefix.equals("ypub") || prefix.equals("upub");
        final String xpub = segwitPrefix ? toXpub(p) : p;

        final String override = scriptTypeCfg == null ? "auto" : scriptTypeCfg.trim().toLowerCase();
        scriptType = resolveScriptType(prefix, override);
        basePath = "m/" + scriptType.purpose + "'/0'/0'";
        final String detection = "auto".equals(override) ? "auto-detected" : "configured";
        LOG.infof("\u20bf Wallet \u2192 %s (BIP%d, %s) [%s], key %s",
                scriptType, scriptType.purpose, basePath, detection, mask(p));
        account = DeterministicKey.deserializeB58(null, xpub, params);
        initialized = true;
    }

    /** Masks the extended public key for logs: keeps the first 8 and last 4 characters. */
    static String mask(String key) {
        if (key.length() <= 12) {
            return "*".repeat(key.length());
        }
        return key.substring(0, 8) + "*".repeat(key.length() - 12) + key.substring(key.length() - 4);
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
        final byte[] data = Base58.decodeChecked(ext); // version(4) + payload, checksum stripped
        final byte[] out = data.clone();
        System.arraycopy(XPUB_VERSION, 0, out, 0, 4);
        final byte[] check = Sha256Hash.hashTwice(out);
        final byte[] full = new byte[out.length + 4];
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
        return addressCache.get(((long) chain << 32) | (index & 0xFFFFFFFFL), key -> deriveAddress(chain, index));
    }

    private AddressInfo deriveAddress(int chain, int index) {
        final DeterministicKey chainKey = chainKeys.computeIfAbsent(chain,
                c -> HDKeyDerivation.deriveChildKey(account, new ChildNumber(c, false)));
        final DeterministicKey key = HDKeyDerivation.deriveChildKey(chainKey, new ChildNumber(index, false));

        final Address addr = switch (scriptType) {
            case P2WPKH -> SegwitAddress.fromKey(params, key);
            case P2TR -> taprootAddress(key);
            case P2SH_P2WPKH -> {
                Script redeem = ScriptBuilder.createP2WPKHOutputScript(key.getPubKeyHash());
                yield LegacyAddress.fromScriptHash(params, CryptoUtils.sha256hash160(redeem.getProgram()));
            }
            case P2PKH -> LegacyAddress.fromKey(params, key);
        };

        final byte[] program = ScriptBuilder.createOutputScript(addr).getProgram();
        final String scripthash = ByteUtils.formatHex(ByteUtils.reverseBytes(Sha256Hash.hash(program)));
        return new AddressInfo(chain, index, addr.toString(), scripthash, ByteUtils.formatHex(program),
                basePath + "/" + chain + "/" + index);
    }

    /** BIP86 key-path-only Taproot: tweak the derived internal key and encode as bech32m (bc1p...). */
    private Address taprootAddress(DeterministicKey key) {
        final ECPoint point = key.getPubKeyPoint().normalize();
        // The x-only internal key implicitly has an even Y coordinate (BIP340/341).
        final ECPoint internal = point.getAffineYCoord().toBigInteger().testBit(0) ? point.negate().normalize() : point;
        final byte[] internalX = to32(internal.getAffineXCoord().toBigInteger());
        final BigInteger tweak = new BigInteger(1, taggedHash("TapTweak", internalX));
        final ECPoint output = ECKey.ecDomainParameters().getG().multiply(tweak).add(internal).normalize();
        final byte[] outputX = to32(output.getAffineXCoord().toBigInteger());
        return SegwitAddress.fromProgram(params, 1, outputX);
    }

    private static byte[] taggedHash(String tag, byte[] msg) {
        final byte[] t = Sha256Hash.hash(tag.getBytes(StandardCharsets.UTF_8));
        final byte[] data = new byte[t.length * 2 + msg.length];
        System.arraycopy(t, 0, data, 0, t.length);
        System.arraycopy(t, 0, data, t.length, t.length);
        System.arraycopy(msg, 0, data, t.length * 2, msg.length);
        return Sha256Hash.hash(data);
    }

    private static byte[] to32(BigInteger v) {
        final byte[] b = v.toByteArray();
        if (b.length == 32) {
            return b;
        }
        final byte[] out = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }
}
