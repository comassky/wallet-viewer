package com.example.walletviewer.service;

import com.example.walletviewer.derivation.HdWallet;
import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.AddressInfo;
import com.example.walletviewer.model.BalanceDto;
import com.example.walletviewer.model.ReceiveAddressDto;
import com.example.walletviewer.model.TransactionDto;
import com.example.walletviewer.model.UtxoDto;
import com.example.walletviewer.model.WalletSnapshot;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a wallet snapshot (balance, UTXOs, transactions, next receive address)
 * by scanning derived addresses against the Electrum server. Every subscription
 * performs a fresh scan; caching, retries and live events belong to the coordinator.
 * Only monotonic address coverage and locally derived watch identities are retained,
 * never snapshots or scan accumulators.
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

    // Process-local watermarks, committed only after an entire scan succeeds.
    private volatile Coverage coverage = new Coverage(-1, -1, 0);

    // Written only by subscribeAddress, never learned from remote notifications or public
    // derive requests. Scan indices are [0, cap) on each chain, plus receive index cap:
    // at most 2 * maxAddresses + 1 identities, retained across failures and reconnects.
    private final Map<String, String> knownAddresses = new ConcurrentHashMap<>();

    private record Coverage(int receiveIndex, int changeIndex, int nextReceiveIndex) {
    }

    /** History is private to one scan, never attached to cached derivation data. */
    private record ScannedAddress(AddressInfo address, JsonArray history) {
        boolean used() {
            return history != null && !history.isEmpty();
        }
    }

    @PostConstruct
    void validateConfiguration() {
        if (gapLimit <= 0 || maxAddresses <= 0) {
            throw new IllegalArgumentException("Wallet scan limits must be positive");
        }
    }

    /** Cold, uncached scan. The coordinator should serialize scans and rescan on reconnect/notifications. */
    public Uni<WalletSnapshot> scan() {
        return Uni.createFrom().deferred(this::build);
    }

    public AddressInfo derive(int chain, int index) {
        return wallet.address(chain, index);
    }

    /** Thread-safe O(1) local lookup; no RPC, derivation or registration. Null means unknown. */
    public String knownAddressForScripthash(String scripthash) {
        return scripthash == null ? null : knownAddresses.get(scripthash);
    }

    private Uni<JsonObject> subscribeAddress(AddressInfo address) {
        // Publish before even constructing the RPC: a notification may precede its reply.
        knownAddresses.put(address.scripthash, address.address);
        return electrum.call("blockchain.scripthash.subscribe", address.scripthash);
    }

    private Uni<WalletSnapshot> build() {
        Coverage previous = coverage;
        Uni<Integer> tipUni = electrum.call("blockchain.headers.subscribe")
                .map(r -> r.getJsonObject("result").getInteger("height"));
        Uni<List<ScannedAddress>> recvUni = scanChain(0, previous.receiveIndex());
        Uni<List<ScannedAddress>> changeUni = scanChain(1, previous.changeIndex());

        return Uni.combine().all().unis(tipUni, recvUni, changeUni).asTuple()
                .flatMap(t -> {
                    int tip = t.getItem1();
                    List<ScannedAddress> receive = t.getItem2();
                    List<ScannedAddress> change = t.getItem3();

                    List<ScannedAddress> all = new ArrayList<>(receive);
                    all.addAll(change);

                    Set<String> ourScripts = new HashSet<>();
                    List<AddressInfo> used = new ArrayList<>();
                    Map<String, Integer> txHeights = new LinkedHashMap<>();
                    for (ScannedAddress a : all) {
                        ourScripts.add(a.address().scriptHex);
                        if (a.used()) {
                            used.add(a.address());
                            for (int i = 0; i < a.history().size(); i++) {
                                JsonObject entry = a.history().getJsonObject(i);
                                txHeights.put(entry.getString("tx_hash"), entry.getInteger("height"));
                            }
                        }
                    }

                    // Do not reuse holes or regress an exposed receive index after history shrinks.
                    int nextIndex = previous.nextReceiveIndex();
                    for (ScannedAddress a : receive) {
                        if (a.used()) {
                            nextIndex = Math.max(nextIndex, a.address().index + 1);
                        }
                    }
                    AddressInfo recv = nextIndex < receive.size()
                            ? receive.get(nextIndex).address() : wallet.address(0, nextIndex);
                    ReceiveAddressDto recvDto = new ReceiveAddressDto(recv.index, recv.address, recv.path);
                    // maxAddresses caps history scans per chain, not the watch count. When the
                    // cap is fully used, watch exactly one extra receive address (without history).
                    Uni<Void> receiveWatch = nextIndex < receive.size()
                            ? Uni.createFrom().voidItem()
                            : subscribeAddress(recv).replaceWithVoid();

                    Uni<BalanceDto> balanceUni = sumBalances(used);
                    Uni<List<UtxoDto>> utxoUni = fetchUtxos(used, tip);
                    Uni<List<TransactionDto>> txUni = fetchTransactions(txHeights, ourScripts, tip);

                    return receiveWatch.flatMap(ignored -> Uni.combine().all().unis(balanceUni, utxoUni, txUni).asTuple()
                            .map(r -> new WalletSnapshot(r.getItem1(), r.getItem2(), r.getItem3(), recvDto)))
                            .invoke(snapshot -> rememberCoverage(receive.size() - 1, change.size() - 1, recv.index));
                });
    }

    private synchronized void rememberCoverage(int receiveIndex, int changeIndex, int nextReceiveIndex) {
        Coverage previous = coverage;
        coverage = new Coverage(Math.max(previous.receiveIndex(), Math.max(receiveIndex, nextReceiveIndex)),
                Math.max(previous.changeIndex(), changeIndex),
                Math.max(previous.nextReceiveIndex(), nextReceiveIndex));
    }

    // ---- address scanning (gap-limit) -------------------------------------

    private Uni<List<ScannedAddress>> scanChain(int chain, int highestIndex) {
        return scanFrom(chain, 0, highestIndex, new ArrayList<>());
    }

    private Uni<List<ScannedAddress>> scanFrom(int chain, int start, int highestIndex, List<ScannedAddress> acc) {
        List<Uni<ScannedAddress>> batch = new ArrayList<>();
        int batchSize = Math.min(gapLimit, maxAddresses - start);
        for (int i = 0; i < batchSize; i++) {
            AddressInfo ai = wallet.address(chain, start + i);
            // Reissue on every scan: the transport is not a persistent subscription registry.
            // Only the public script hash is sent, never the address, derivation path or key.
            batch.add(subscribeAddress(ai)
                    .flatMap(ignored -> electrum.call("blockchain.scripthash.get_history", ai.scripthash))
                    .map(r -> new ScannedAddress(ai, r.getJsonArray("result"))));
        }
        return Uni.join().all(batch).andFailFast().flatMap(list -> {
            acc.addAll(list);
            int trailing = 0;
            for (int i = acc.size() - 1; i >= 0 && !acc.get(i).used(); i--) {
                trailing++;
            }
            if (acc.size() >= maxAddresses || (trailing >= gapLimit && acc.size() - 1 >= highestIndex)) {
                return Uni.createFrom().item(acc);
            }
            return scanFrom(chain, start + batchSize, highestIndex, acc);
        });
    }

    // ---- balance ----------------------------------------------------------

    private Uni<BalanceDto> sumBalances(List<AddressInfo> used) {
        if (used.isEmpty()) {
            return Uni.createFrom().item(new BalanceDto(0, 0));
        }
        List<Uni<BalanceDto>> unis = new ArrayList<>();
        for (AddressInfo a : used) {
            unis.add(electrum.call("blockchain.scripthash.get_balance", a.scripthash)
                    .map(r -> {
                        JsonObject o = r.getJsonObject("result");
                        return new BalanceDto(o.getLong("confirmed", 0L), o.getLong("unconfirmed", 0L));
                    }));
        }
        return Uni.join().all(unis).andFailFast().map(list -> {
            long confirmed = 0, unconfirmed = 0;
            for (BalanceDto balance : list) {
                confirmed += balance.confirmed();
                unconfirmed += balance.unconfirmed();
            }
            return new BalanceDto(confirmed, unconfirmed);
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

        List<Integer> heights = txHeights.values().stream().filter(h -> h > 0).distinct().toList();
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
                parsed.put(ids.get(i), Transaction.read(ByteBuffer.wrap(ByteUtils.parseHex(rawTxs.get(i)))));
            }

            // Map of our own outputs "txid:vout" -> satoshis, to detect our spends without prevout lookups.
            Map<String, Long> ourOut = new HashMap<>();
            for (Map.Entry<String, Transaction> e : parsed.entrySet()) {
                for (TransactionOutput o : e.getValue().getOutputs()) {
                    if (ourScripts.contains(ByteUtils.formatHex(o.getScriptBytes()))) {
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
                    if (ourScripts.contains(ByteUtils.formatHex(o.getScriptBytes()))) {
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

    /** Decodes the 80-byte block header and returns its timestamp in Unix seconds. */
    private static long headerTime(String headerHex) {
        return Block.read(ByteBuffer.wrap(ByteUtils.parseHex(headerHex))).getTimeSeconds();
    }
}

