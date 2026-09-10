package com.comassky.wallet.service;

import com.comassky.wallet.derivation.HdWallet;
import com.comassky.wallet.electrum.ElectrumClient;
import com.comassky.wallet.electrum.ElectrumMethod;
import com.comassky.wallet.model.AddressCheckDto;
import com.comassky.wallet.model.AddressInfo;
import com.comassky.wallet.model.BalanceDto;
import com.comassky.wallet.model.ReceiveAddressDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.TransactionType;
import com.comassky.wallet.model.UtxoDto;
import com.comassky.wallet.model.WalletSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    // Raw transaction bytes are committed by their txid, and a buried block header time never
    // changes: both are safe to reuse across the full rescan triggered by every notification.
    private static final int HEADER_MATURITY = 6; // blocks below the tip before a header time is cached
    private final Cache<String, String> rawTxCache = Caffeine.newBuilder().maximumSize(20_000).build();
    private final Cache<Integer, Long> headerTimeCache = Caffeine.newBuilder().maximumSize(20_000).build();

    // Unconfirmed first (treated as the highest height), then newest confirmed height, then newest timestamp.
    private static final Comparator<TransactionDto> TRANSACTION_ORDER =
            Comparator.comparingInt((TransactionDto t) -> t.height() <= 0 ? Integer.MAX_VALUE : t.height())
                    .reversed()
                    .thenComparing(t -> t.timestamp() == null ? 0L : t.timestamp(), Comparator.reverseOrder());

    private record Coverage(int receiveIndex, int changeIndex, int nextReceiveIndex) {
    }

    /** One wallet-owned output; used to total received amounts and detect our own spends. */
    private record OwnedOutput(String txid, long index, long value, String address) {
        String outpoint() {
            return txid + ":" + index;
        }
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

    /**
     * Checks whether an address is derived from the configured key by comparing it against the
     * first {@code maxAddresses} receive (chain 0) and change (chain 1) addresses. Purely local.
     */
    public AddressCheckDto verifyAddress(String address) {
        String target = address == null ? "" : address.trim();
        if (target.isEmpty() || target.length() > 128) {
            throw new BadRequestException("Provide a Bitcoin address to verify");
        }
        for (int chain = 0; chain <= 1; chain++) {
            for (int index = 0; index < maxAddresses; index++) {
                AddressInfo info = wallet.address(chain, index);
                if (info.address.equals(target)) {
                    return new AddressCheckDto(info.address, true, chain, index, info.path, maxAddresses);
                }
            }
        }
        return new AddressCheckDto(target, false, null, null, null, maxAddresses);
    }

    /** Thread-safe O(1) local lookup; no RPC, derivation or registration. Null means unknown. */
    public String knownAddressForScripthash(String scripthash) {
        return scripthash == null ? null : knownAddresses.get(scripthash);
    }

    private Uni<JsonObject> subscribeAddress(AddressInfo address) {
        // Publish before even constructing the RPC: a notification may precede its reply.
        knownAddresses.put(address.scripthash, address.address);
        return electrum.call(ElectrumMethod.SCRIPTHASH_SUBSCRIBE, address.scripthash);
    }

    private Uni<WalletSnapshot> build() {
        Coverage previous = coverage;
        Uni<Integer> tipUni = electrum.call(ElectrumMethod.HEADERS_SUBSCRIBE)
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

                    Set<String> ourScripts = all.stream()
                            .map(a -> a.address().scriptHex)
                            .collect(Collectors.toSet());
                    Map<String, String> addressByScript = all.stream()
                            .collect(Collectors.toMap(a -> a.address().scriptHex, a -> a.address().address, (x, y) -> x));
                    List<AddressInfo> used = new ArrayList<>();
                    Map<String, Integer> txHeights = new LinkedHashMap<>();
                    all.stream().filter(ScannedAddress::used).forEach(a -> {
                        used.add(a.address());
                        a.history().stream()
                                .map(JsonObject.class::cast)
                                .forEach(entry -> txHeights.put(entry.getString("tx_hash"), entry.getInteger("height")));
                    });

                    // Do not reuse holes or regress an exposed receive index after history shrinks.
                    int nextIndex = receive.stream()
                            .filter(ScannedAddress::used)
                            .mapToInt(a -> a.address().index + 1)
                            .reduce(previous.nextReceiveIndex(), Math::max);
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
                    Uni<List<TransactionDto>> txUni = fetchTransactions(txHeights, ourScripts, addressByScript, tip);
                        WalletSnapshot.Discovery discovery = new WalletSnapshot.Discovery(
                            hasUnusedGap(receive) && hasUnusedGap(change) && nextIndex < receive.size(),
                            receive.size(), change.size(), maxAddresses, gapLimit);

                    return receiveWatch.flatMap(ignored -> Uni.combine().all().unis(balanceUni, utxoUni, txUni).asTuple()
                            .map(r -> new WalletSnapshot(r.getItem1(), r.getItem2(), r.getItem3(), recvDto, discovery)))
                            .invoke(snapshot -> rememberCoverage(receive.size() - 1, change.size() - 1, recv.index));
                });
    }

    private synchronized void rememberCoverage(int receiveIndex, int changeIndex, int nextReceiveIndex) {
        Coverage previous = coverage;
        coverage = new Coverage(Math.max(previous.receiveIndex(), Math.max(receiveIndex, nextReceiveIndex)),
                Math.max(previous.changeIndex(), changeIndex),
                Math.max(previous.nextReceiveIndex(), nextReceiveIndex));
    }

    private boolean hasUnusedGap(List<ScannedAddress> addresses) {
        return addresses.size() >= gapLimit && addresses.subList(addresses.size() - gapLimit, addresses.size())
                .stream().noneMatch(ScannedAddress::used);
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
                    .flatMap(ignored -> electrum.call(ElectrumMethod.SCRIPTHASH_GET_HISTORY, ai.scripthash))
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
        List<Uni<BalanceDto>> unis = used.stream()
                .map(a -> electrum.call(ElectrumMethod.SCRIPTHASH_GET_BALANCE, a.scripthash).map(r -> {
                    JsonObject o = r.getJsonObject("result");
                    return new BalanceDto(o.getLong("confirmed", 0L), o.getLong("unconfirmed", 0L));
                }))
                .toList();
        return Uni.join().all(unis).andFailFast().map(list -> new BalanceDto(
                list.stream().mapToLong(BalanceDto::confirmed).sum(),
                list.stream().mapToLong(BalanceDto::unconfirmed).sum()));
    }

    // ---- UTXOs ------------------------------------------------------------

    private Uni<List<UtxoDto>> fetchUtxos(List<AddressInfo> used, int tip) {
        if (used.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<Uni<List<UtxoDto>>> unis = used.stream()
                .map(a -> electrum.call(ElectrumMethod.SCRIPTHASH_LISTUNSPENT, a.scripthash)
                        .map(r -> r.getJsonArray("result").stream()
                                .map(JsonObject.class::cast)
                                .map(u -> {
                                    int h = u.getInteger("height", 0);
                                    int conf = h > 0 ? tip - h + 1 : 0;
                                    return new UtxoDto(u.getString("tx_hash"), u.getInteger("tx_pos"),
                                            u.getLong("value"), h, conf, a.address);
                                })
                                .toList()))
                .toList();
        return Uni.join().all(unis).andFailFast().map(lists -> lists.stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingLong(UtxoDto::value).reversed())
                .toList());
    }

    // ---- transactions -----------------------------------------------------

    /**
     * Fetches raw transactions and decodes them locally, so it works on every Electrum
     * server (verbose transaction.get is not universally supported, e.g. esplora electrs).
     */
    private Uni<List<TransactionDto>> fetchTransactions(Map<String, Integer> txHeights,
                                                        Set<String> ourScripts, Map<String, String> addressByScript, int tip) {
        if (txHeights.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<Integer> heights = txHeights.values().stream().filter(h -> h > 0).distinct().toList();
        // A buried block header time is immutable: only fetch heights missing from the cache.
        Map<Integer, Long> timeByHeight = new HashMap<>();
        List<Integer> heightMisses = new ArrayList<>();
        heights.forEach(h -> {
            Long cached = headerTimeCache.getIfPresent(h);
            if (cached != null) {
                timeByHeight.put(h, cached);
            } else {
                heightMisses.add(h);
            }
        });
        List<Uni<Long>> headerUnis = heightMisses.stream()
                .map(h -> electrum.call(ElectrumMethod.BLOCK_HEADER, h).map(r -> headerTime(r.getString("result"))))
                .toList();

        // The txid commits a transaction's bytes, so cached raw copies stay valid every scan.
        List<String> ids = List.copyOf(txHeights.keySet());
        Map<String, String> rawById = new HashMap<>();
        List<String> txMisses = new ArrayList<>();
        ids.forEach(id -> {
            String cached = rawTxCache.getIfPresent(id);
            if (cached != null) {
                rawById.put(id, cached);
            } else {
                txMisses.add(id);
            }
        });
        List<Uni<String>> txUnis = txMisses.stream()
                .map(id -> electrum.call(ElectrumMethod.TRANSACTION_GET, id).map(r -> r.getString("result")))
                .toList();

        Uni<List<Long>> headersUni = heightMisses.isEmpty()
                ? Uni.createFrom().item(List.of())
                : Uni.join().all(headerUnis).andFailFast();
        Uni<List<String>> rawTxUni = txMisses.isEmpty()
                ? Uni.createFrom().item(List.of())
                : Uni.join().all(txUnis).andFailFast();

        return Uni.combine().all().unis(headersUni, rawTxUni).asTuple().map(t -> {
            List<Long> headerTimes = t.getItem1();
            List<String> rawTxs = t.getItem2();

            IntStream.range(0, heightMisses.size()).forEach(i -> {
                int h = heightMisses.get(i);
                long time = headerTimes.get(i);
                timeByHeight.put(h, time);
                if (tip - h >= HEADER_MATURITY) {
                    headerTimeCache.put(h, time); // Never cache reorg-prone heights near the tip.
                }
            });
            IntStream.range(0, txMisses.size()).forEach(i -> {
                String id = txMisses.get(i);
                rawTxCache.put(id, rawTxs.get(i));
                rawById.put(id, rawTxs.get(i));
            });

            Map<String, Transaction> parsed = ids.stream().collect(Collectors.toMap(
                    id -> id, id -> Transaction.read(ByteBuffer.wrap(ByteUtils.parseHex(rawById.get(id)))),
                    (a, b) -> a, LinkedHashMap::new));

            // One hash per output: our outputs give both received totals and the outpoints we can spend.
            List<OwnedOutput> owned = parsed.entrySet().stream()
                    .flatMap(e -> e.getValue().getOutputs().stream()
                            .filter(o -> ourScripts.contains(ByteUtils.formatHex(o.getScriptBytes())))
                            .map(o -> new OwnedOutput(e.getKey(), o.getIndex(), o.getValue().value,
                                    addressByScript.get(ByteUtils.formatHex(o.getScriptBytes())))))
                    .toList();
            Map<String, Long> receivedByTx = owned.stream()
                    .collect(Collectors.groupingBy(OwnedOutput::txid, Collectors.summingLong(OwnedOutput::value)));
            Map<String, Long> ourOut = owned.stream()
                    .collect(Collectors.toMap(OwnedOutput::outpoint, OwnedOutput::value));
            // Addresses credited by each tx (our owned outputs) and the address behind each owned outpoint.
            Map<String, Set<String>> receivedAddressesByTx = owned.stream()
                    .filter(o -> o.address() != null)
                    .collect(Collectors.groupingBy(OwnedOutput::txid,
                            Collectors.mapping(OwnedOutput::address, Collectors.toCollection(LinkedHashSet::new))));
            Map<String, String> addressByOutpoint = owned.stream()
                    .filter(o -> o.address() != null)
                    .collect(Collectors.toMap(OwnedOutput::outpoint, OwnedOutput::address, (x, y) -> x));

            return parsed.entrySet().stream()
                    .map(e -> toTransaction(e.getKey(), e.getValue(), receivedByTx, ourOut,
                            receivedAddressesByTx, addressByOutpoint, txHeights, timeByHeight, tip))
                    .sorted(TRANSACTION_ORDER)
                    .toList();
        });
    }

    private static TransactionDto toTransaction(String txid, Transaction tx, Map<String, Long> receivedByTx,
                                                Map<String, Long> ourOut, Map<String, Set<String>> receivedAddressesByTx,
                                                Map<String, String> addressByOutpoint, Map<String, Integer> txHeights,
                                                Map<Integer, Long> timeByHeight, int tip) {
        final long received = receivedByTx.getOrDefault(txid, 0L);
        final long sent = spentByUs(tx, ourOut);
        final long net = received - sent;
        final int height = txHeights.getOrDefault(txid, 0);
        final int conf = height > 0 ? tip - height + 1 : 0;
        final Long ts = height > 0 ? timeByHeight.get(height) : null;
        final boolean allInputsOwned = !tx.getInputs().isEmpty() && tx.getInputs().stream()
            .allMatch(input -> !input.isCoinBase()
                && ourOut.containsKey(input.getOutpoint().getHash() + ":" + input.getOutpoint().getIndex()));
        final boolean allOutputsOwned = !tx.getOutputs().isEmpty() && tx.getOutputs().stream()
            .allMatch(output -> ourOut.containsKey(txid + ":" + output.getIndex()));
        final TransactionType type = allInputsOwned && allOutputsOwned ? TransactionType.SELF : switch (Long.signum(net)) {
            case 1 -> TransactionType.RECEIVED;
            case -1 -> TransactionType.SENT;
            default -> allOutputsOwned ? TransactionType.RECEIVED : TransactionType.SENT;
        };
        return new TransactionDto(txid, net, received, sent, height, conf, ts, type,
                involvedAddresses(tx, txid, receivedAddressesByTx, addressByOutpoint));
    }

    /** Wallet addresses credited (owned outputs) or debited (spent owned inputs) by this transaction. */
    private static List<String> involvedAddresses(Transaction tx, String txid,
                                                  Map<String, Set<String>> receivedAddressesByTx,
                                                  Map<String, String> addressByOutpoint) {
        final Set<String> addresses = new LinkedHashSet<>(receivedAddressesByTx.getOrDefault(txid, Set.of()));
        tx.getInputs().stream()
                .filter(in -> !in.isCoinBase())
                .map(in -> addressByOutpoint.get(in.getOutpoint().getHash() + ":" + in.getOutpoint().getIndex()))
                .filter(Objects::nonNull)
                .forEach(addresses::add);
        return List.copyOf(addresses);
    }

    /** Sums the value of prior outputs of ours that this transaction spends, ignoring coinbase inputs. */
    private static long spentByUs(Transaction tx, Map<String, Long> ourOut) {
        return tx.getInputs().stream()
                .filter(in -> !in.isCoinBase())
                .map(in -> ourOut.get(in.getOutpoint().getHash() + ":" + in.getOutpoint().getIndex()))
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    /** Decodes the 80-byte block header and returns its timestamp in Unix seconds. */
    private static long headerTime(String headerHex) {
        return Block.read(ByteBuffer.wrap(ByteUtils.parseHex(headerHex))).getTimeSeconds();
    }
}

