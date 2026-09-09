package com.example.walletviewer;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a wallet snapshot (balance, UTXOs, transactions, next receive address)
 * by scanning derived addresses against the Electrum server. Results are cached
 * for a short TTL to avoid hammering electrs.
 */
@ApplicationScoped
public class WalletService {

    @Inject
    ElectrumClient electrum;
    @Inject
    HdWallet wallet;

    @ConfigProperty(name = "wallet.gap-limit", defaultValue = "20")
    int gapLimit;
    @ConfigProperty(name = "wallet.max-addresses", defaultValue = "200")
    int maxAddresses;
    @ConfigProperty(name = "wallet.cache-ttl-seconds", defaultValue = "30")
    int cacheTtl;

    private volatile Uni<WalletSnapshot> cache;

    public Uni<WalletSnapshot> snapshot() {
        Uni<WalletSnapshot> c = cache;
        if (c == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = build().memoize().atLeast(Duration.ofSeconds(cacheTtl));
                }
                c = cache;
            }
        }
        return c;
    }

    public AddressInfo derive(int chain, int index) {
        return wallet.address(chain, index);
    }

    private Uni<WalletSnapshot> build() {
        Uni<Integer> tipUni = electrum.call("blockchain.headers.subscribe")
                .map(r -> r.getJsonObject("result").getInteger("height"));
        Uni<List<AddressInfo>> recvUni = scanChain(0);
        Uni<List<AddressInfo>> changeUni = scanChain(1);

        return Uni.combine().all().unis(tipUni, recvUni, changeUni).asTuple()
                .flatMap(t -> {
                    int tip = t.getItem1();
                    List<AddressInfo> receive = t.getItem2();
                    List<AddressInfo> change = t.getItem3();

                    List<AddressInfo> all = new ArrayList<>();
                    all.addAll(receive);
                    all.addAll(change);

                    Set<String> ourScripts = new HashSet<>();
                    List<AddressInfo> used = new ArrayList<>();
                    for (AddressInfo a : all) {
                        ourScripts.add(a.scriptHex);
                        if (a.used()) {
                            used.add(a);
                        }
                    }

                    // Next receive address = first unused external address
                    AddressInfo recv = null;
                    for (AddressInfo a : receive) {
                        if (!a.used()) {
                            recv = a;
                            break;
                        }
                    }
                    if (recv == null) {
                        recv = wallet.address(0, receive.size());
                    }
                    ReceiveAddressDto recvDto = new ReceiveAddressDto(recv.index, recv.address, recv.path);

                    // Collect tx ids + heights from address histories
                    Map<String, Integer> txHeights = new LinkedHashMap<>();
                    for (AddressInfo a : used) {
                        for (int i = 0; i < a.history.size(); i++) {
                            JsonObject e = a.history.getJsonObject(i);
                            txHeights.put(e.getString("tx_hash"), e.getInteger("height"));
                        }
                    }

                    Uni<long[]> balanceUni = sumBalances(used);
                    Uni<List<UtxoDto>> utxoUni = fetchUtxos(used, tip);
                    Uni<List<TransactionDto>> txUni = fetchTransactions(txHeights, ourScripts, tip);

                    return Uni.combine().all().unis(balanceUni, utxoUni, txUni).asTuple()
                            .map(r -> {
                                long[] bal = r.getItem1();
                                BalanceDto balance = new BalanceDto(bal[0], bal[1], bal[0] + bal[1]);
                                return new WalletSnapshot(balance, r.getItem2(), r.getItem3(), recvDto);
                            });
                });
    }

    // ---- address scanning (gap-limit) -------------------------------------

    private Uni<List<AddressInfo>> scanChain(int chain) {
        return scanFrom(chain, 0, new ArrayList<>());
    }

    private Uni<List<AddressInfo>> scanFrom(int chain, int start, List<AddressInfo> acc) {
        List<Uni<AddressInfo>> batch = new ArrayList<>();
        for (int i = 0; i < gapLimit; i++) {
            AddressInfo ai = wallet.address(chain, start + i);
            batch.add(electrum.call("blockchain.scripthash.get_history", ai.scripthash)
                    .map(r -> {
                        ai.history = r.getJsonArray("result");
                        return ai;
                    }));
        }
        return Uni.join().all(batch).andFailFast().flatMap(list -> {
            acc.addAll(list);
            int trailing = 0;
            for (int i = acc.size() - 1; i >= 0 && !acc.get(i).used(); i--) {
                trailing++;
            }
            if (trailing >= gapLimit || acc.size() >= maxAddresses) {
                return Uni.createFrom().item(acc);
            }
            return scanFrom(chain, start + gapLimit, acc);
        });
    }

    // ---- balance ----------------------------------------------------------

    private Uni<long[]> sumBalances(List<AddressInfo> used) {
        if (used.isEmpty()) {
            return Uni.createFrom().item(new long[]{0, 0});
        }
        List<Uni<long[]>> unis = new ArrayList<>();
        for (AddressInfo a : used) {
            unis.add(electrum.call("blockchain.scripthash.get_balance", a.scripthash)
                    .map(r -> {
                        JsonObject o = r.getJsonObject("result");
                        return new long[]{o.getLong("confirmed", 0L), o.getLong("unconfirmed", 0L)};
                    }));
        }
        return Uni.join().all(unis).andFailFast().map(list -> {
            long c = 0, u = 0;
            for (long[] b : list) {
                c += b[0];
                u += b[1];
            }
            return new long[]{c, u};
        });
    }

    // ---- UTXOs ------------------------------------------------------------

    private Uni<List<UtxoDto>> fetchUtxos(List<AddressInfo> used, int tip) {
        if (used.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<Uni<List<UtxoDto>>> unis = new ArrayList<>();
        for (AddressInfo a : used) {
            unis.add(electrum.call("blockchain.scripthash.listunspent", a.scripthash)
                    .map(r -> {
                        JsonArray arr = r.getJsonArray("result");
                        List<UtxoDto> out = new ArrayList<>();
                        for (int i = 0; i < arr.size(); i++) {
                            JsonObject u = arr.getJsonObject(i);
                            int h = u.getInteger("height", 0);
                            int conf = h > 0 ? tip - h + 1 : 0;
                            out.add(new UtxoDto(u.getString("tx_hash"), u.getInteger("tx_pos"),
                                    u.getLong("value"), h, conf, a.address));
                        }
                        return out;
                    }));
        }
        return Uni.join().all(unis).andFailFast().map(lists -> {
            List<UtxoDto> out = new ArrayList<>();
            for (List<UtxoDto> l : lists) {
                out.addAll(l);
            }
            out.sort((x, y) -> Long.compare(y.value(), x.value()));
            return out;
        });
    }

    // ---- transactions -----------------------------------------------------

    /**
     * Fetches raw transactions and decodes them locally, so it works on every Electrum
     * server (verbose transaction.get is not universally supported, e.g. esplora electrs).
     */
    private Uni<List<TransactionDto>> fetchTransactions(Map<String, Integer> txHeights,
                                                        Set<String> ourScripts, int tip) {
        if (txHeights.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        NetworkParameters params = wallet.params();

        List<Integer> heights = new ArrayList<>(new HashSet<>(txHeights.values()));
        heights.removeIf(h -> h <= 0);
        List<Uni<Long>> headerUnis = new ArrayList<>();
        for (int h : heights) {
            headerUnis.add(electrum.call("blockchain.block.header", h)
                    .map(r -> headerTime(r.getString("result"))));
        }

        List<String> ids = new ArrayList<>(txHeights.keySet());
        List<Uni<String>> txUnis = new ArrayList<>();
        for (String id : ids) {
            txUnis.add(electrum.call("blockchain.transaction.get", id).map(r -> r.getString("result")));
        }

        Uni<List<Long>> headersUni = heights.isEmpty()
                ? Uni.createFrom().item(List.of())
                : Uni.join().all(headerUnis).andFailFast();
        Uni<List<String>> rawTxUni = Uni.join().all(txUnis).andFailFast();

        return Uni.combine().all().unis(headersUni, rawTxUni).asTuple().map(t -> {
            List<Long> headerTimes = t.getItem1();
            List<String> rawTxs = t.getItem2();

            Map<Integer, Long> timeByHeight = new HashMap<>();
            for (int i = 0; i < heights.size(); i++) {
                timeByHeight.put(heights.get(i), headerTimes.get(i));
            }

            Map<String, Transaction> parsed = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                parsed.put(ids.get(i), new Transaction(params, Utils.HEX.decode(rawTxs.get(i))));
            }

            // Map of our own outputs "txid:vout" -> satoshis, to detect our spends without prevout lookups.
            Map<String, Long> ourOut = new HashMap<>();
            for (Map.Entry<String, Transaction> e : parsed.entrySet()) {
                for (TransactionOutput o : e.getValue().getOutputs()) {
                    if (ourScripts.contains(Utils.HEX.encode(o.getScriptBytes()))) {
                        ourOut.put(e.getKey() + ":" + o.getIndex(), o.getValue().value);
                    }
                }
            }

            List<TransactionDto> result = new ArrayList<>();
            for (Map.Entry<String, Transaction> e : parsed.entrySet()) {
                String txid = e.getKey();
                Transaction tx = e.getValue();
                long received = 0, sent = 0;

                for (TransactionOutput o : tx.getOutputs()) {
                    if (ourScripts.contains(Utils.HEX.encode(o.getScriptBytes()))) {
                        received += o.getValue().value;
                    }
                }
                for (TransactionInput in : tx.getInputs()) {
                    if (in.isCoinBase()) {
                        continue;
                    }
                    Long v = ourOut.get(in.getOutpoint().getHash() + ":" + in.getOutpoint().getIndex());
                    if (v != null) {
                        sent += v;
                    }
                }

                long net = received - sent;
                int height = txHeights.getOrDefault(txid, 0);
                int conf = height > 0 ? tip - height + 1 : 0;
                Long ts = height > 0 ? timeByHeight.get(height) : null;
                String type = net > 0 ? "received" : (net < 0 ? "sent" : "self");
                result.add(new TransactionDto(txid, net, received, sent, height, conf, ts, type));
            }

            result.sort((a, b) -> {
                int ha = a.height() <= 0 ? Integer.MAX_VALUE : a.height();
                int hb = b.height() <= 0 ? Integer.MAX_VALUE : b.height();
                if (ha != hb) {
                    return Integer.compare(hb, ha);
                }
                long ta = a.timestamp() == null ? 0 : a.timestamp();
                long tb = b.timestamp() == null ? 0 : b.timestamp();
                return Long.compare(tb, ta);
            });
            return result;
        });
    }

    /** Extracts the block timestamp (uint32 LE at byte offset 68) from an 80-byte header hex. */
    private static long headerTime(String headerHex) {
        long b0 = Long.parseLong(headerHex.substring(136, 138), 16);
        long b1 = Long.parseLong(headerHex.substring(138, 140), 16);
        long b2 = Long.parseLong(headerHex.substring(140, 142), 16);
        long b3 = Long.parseLong(headerHex.substring(142, 144), 16);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
}

