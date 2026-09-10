package com.comassky.wallet.service;

import com.comassky.wallet.derivation.HdWallet;
import com.comassky.wallet.electrum.ElectrumClient;
import com.comassky.wallet.model.AddressCheckDto;
import com.comassky.wallet.model.AddressInfo;
import com.comassky.wallet.model.BalanceDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.TransactionType;
import com.comassky.wallet.model.UtxoDto;
import com.comassky.wallet.model.WalletSnapshot;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.BadRequestException;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {

    @Test
    void watchMappingPrecedesSubscriptionsAndStaysBoundedAcrossScans() throws Exception {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum);
        electrum.beforeSubscribe = hash -> {
            // Simulate a notification on another thread before the subscribe reply,
            // including the extra receive watch outside the history scan cap.
            String address = CompletableFuture.supplyAsync(() -> service.knownAddressForScripthash(hash))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals("address:" + hash, address);
        };
        assertNull(service.knownAddressForScripthash("0:0"));
        for (int pass = 0; pass < 3; pass++) {
            electrum.resetCalls();
            await(service.scan());
            for (int chain = 0; chain < 2; chain++) {
                for (int index = 0; index < service.maxAddresses; index++) {
                    String hash = chain + ":" + index;
                    assertEquals("address:" + hash, service.knownAddressForScripthash(hash));
                }
            }
            assertEquals("address:0:5", service.knownAddressForScripthash("0:5"));
            assertEquals(11, electrum.subscribed.size());
            assertFalse(electrum.scanned.contains("0:5"));
            electrum.used = index -> false; // Mapping survives history shrink / fresh connections.
        }
        assertNull(service.knownAddressForScripthash("1:5"));
        assertNull(service.knownAddressForScripthash(electrum.transaction.getTxId().toString()));
        service.derive(0, 1000); // Public derivation must not grow the watch registry.
        assertNull(service.knownAddressForScripthash("0:1000"));

        // Lookup remains purely local even when neither dependency is available.
        service.wallet = null;
        service.electrum = null;
        assertEquals("address:0:5", service.knownAddressForScripthash("0:5"));
        assertNull(service.knownAddressForScripthash(null));
        for (int i = 0; i < 1000; i++) {
            assertNull(service.knownAddressForScripthash("remote-" + i));
        }
        var field = WalletService.class.getDeclaredField("knownAddresses");
        field.setAccessible(true);
        assertEquals(2 * service.maxAddresses + 1, ((Map<?, ?>) field.get(service)).size());
    }

    @Test
    void scansAreLazyAndRespectANonMultipleAddressLimit() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum);
        Uni<WalletSnapshot> scan = service.scan();
        assertTrue(electrum.calls.isEmpty());
        assertTrue(electrum.scanned.isEmpty());

        WalletSnapshot result = await(scan);
        assertEquals(new WalletSnapshot.Discovery(false, 5, 5, 5, 2), result.discovery());
        assertEquals(5, result.receiveAddress().index());
        assertEquals(10, electrum.scanned.size()); // Five addresses per chain, not six.
        assertFalse(electrum.scanned.contains("0:5"));
        assertFalse(electrum.scanned.contains("1:5"));
        assertEquals(11, electrum.subscribed.size()); // Scan cap is not a watch cap.
        assertTrue(electrum.subscribed.contains("0:5"));
        assertFalse(electrum.subscribed.contains("1:5"));
        assertSubscriptionsBeforeHistory(electrum);

        electrum.resetCalls();
        assertNotSame(result, await(scan));
        assertEquals(10, electrum.scanned.size()); // Fresh accumulator for each subscription.
        assertEquals(11, electrum.subscribed.size()); // Reissue even the extra watch.
        assertSubscriptionsBeforeHistory(electrum);
    }

    @Test
    void separateScansNeverCacheSnapshotsAndResubscribeAllHashes() {
        StubElectrum electrum = new StubElectrum(index -> false);
        WalletService service = service(electrum);
        WalletSnapshot first = await(service.scan());
        assertEquals(new WalletSnapshot.Discovery(true, 2, 2, 5, 2), first.discovery());
        assertEquals(4, electrum.scanned.size());
        assertEquals(4, electrum.subscribed.size());
        assertEquals(0, first.receiveAddress().index());
        assertSubscriptionsBeforeHistory(electrum);

        electrum.resetCalls();
        WalletSnapshot second = await(service.scan());
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(4, electrum.scanned.size());
        assertTrue(electrum.scanned.containsAll(List.of("0:0", "0:1", "1:0", "1:1")));
        assertEquals(4, electrum.subscribed.size());
        assertSubscriptionsBeforeHistory(electrum);
    }

    @Test
    void eachSubscriptionHasFreshHistoriesAndAccumulators() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum);
        assertSame(service.derive(0, 0), service.derive(0, 0)); // Reused derivations must never carry scan state.
        Uni<WalletSnapshot> scan = service.scan();
        WalletSnapshot first = await(scan);
        assertEquals(10_000L, first.balance().total());
        assertEquals(10, first.utxos().size());
        assertEquals(1, first.transactions().size());

        electrum.used = index -> false;
        electrum.resetCalls();
        WalletSnapshot refreshed = await(scan);
        assertNotSame(first, refreshed);
        assertEquals(0L, refreshed.balance().total());
        assertTrue(refreshed.utxos().isEmpty());
        assertTrue(refreshed.transactions().isEmpty());
        assertEquals(10, electrum.scanned.size());
        assertEquals(5, refreshed.receiveAddress().index());
        assertEquals(11, electrum.subscribed.size());
        assertTrue(electrum.subscribed.contains("0:5"));
        assertSubscriptionsBeforeHistory(electrum);
        assertEquals(10_000L, first.balance().total()); // Earlier snapshots remain detached.
        assertEquals(10, first.utxos().size());
        assertEquals(1, first.transactions().size());
    }

    @Test
    void useExtendsBothChainGapsAndReceiveAddressDoesNotReuseAHole() {
        StubElectrum electrum = new StubElectrum(index -> index == 1);
        WalletSnapshot result = await(service(electrum).scan());
        assertEquals(2, result.receiveAddress().index());
        for (int chain = 0; chain < 2; chain++) {
            for (int index = 0; index < 4; index++) {
                assertTrue(electrum.scanned.contains(chain + ":" + index));
            }
            assertFalse(electrum.scanned.contains(chain + ":4"));
        }
        assertEquals(8, electrum.scanned.size());
        assertEquals(8, electrum.subscribed.size());
        assertSubscriptionsBeforeHistory(electrum);
    }

    @Test
    void successfulScanCoverageAndReceiveIndexSurviveHistoryShrink() {
        StubElectrum electrum = new StubElectrum(index -> index == 1);
        WalletService service = service(electrum);
        assertEquals(2, await(service.scan()).receiveAddress().index());

        electrum.used = index -> false;
        electrum.resetCalls(); // Simulate a connection with no remembered subscriptions.
        WalletSnapshot shrunk = await(service.scan());
        assertEquals(2, shrunk.receiveAddress().index());
        assertEquals(8, electrum.scanned.size());
        assertTrue(electrum.subscribed.containsAll(List.of("0:3", "1:3")));
        assertSubscriptionsBeforeHistory(electrum);

        electrum.used = index -> index == 3;
        electrum.resetCalls();
        assertEquals(4, await(service.scan()).receiveAddress().index());
        assertEquals(10, electrum.scanned.size());
        assertEquals(10, electrum.subscribed.size());
        assertSubscriptionsBeforeHistory(electrum);
    }

    @Test
    void failedScanDoesNotCommitCoverageOrReceiveIndex() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum);
        electrum.failMethod = "blockchain.transaction.get";
        assertThrows(IllegalStateException.class, () -> await(service.scan()));
        assertTrue(electrum.subscribed.contains("0:5"));
        assertEquals("address:0:5", service.knownAddressForScripthash("0:5"),
            "A failed scan must retain identities of already issued watches");

        electrum.failMethod = null;
        electrum.used = index -> false;
        electrum.resetCalls();
        assertEquals(0, await(service.scan()).receiveAddress().index());
        assertEquals(4, electrum.scanned.size());
        assertEquals(4, electrum.subscribed.size());
        assertSubscriptionsBeforeHistory(electrum);
    }

    @Test
    void failedSubscriptionPreventsHistoryRequest() {
        StubElectrum electrum = new StubElectrum(index -> false);
        electrum.failMethod = "blockchain.scripthash.subscribe";
        electrum.failHash = "0:0";
        assertThrows(IllegalStateException.class, () -> await(service(electrum).scan()));
        assertFalse(electrum.scanned.contains("0:0"));
    }

    @Test
    void extraReceiveSubscriptionMustSucceedBeforePublishingSnapshot() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum);
        electrum.failMethod = "blockchain.scripthash.subscribe";
        electrum.failHash = "0:5";
        assertThrows(IllegalStateException.class, () -> await(service.scan()));
        assertEquals(10, electrum.scanned.size());
        assertFalse(electrum.scanned.contains("0:5"));

        electrum.failMethod = null;
        electrum.used = index -> false;
        electrum.resetCalls();
        assertEquals(0, await(service.scan()).receiveAddress().index());
        assertEquals(4, electrum.subscribed.size());
    }

    @Test
    void snapshotListsAreDefensiveImmutableCopies() {
        WalletSnapshot result = await(service(new StubElectrum(index -> true)).scan());
        List<UtxoDto> utxos = new ArrayList<>(result.utxos());
        List<TransactionDto> transactions = new ArrayList<>(result.transactions());
        WalletSnapshot copy = new WalletSnapshot(result.balance(), utxos, transactions, result.receiveAddress(), result.discovery());
        utxos.clear();
        transactions.clear();
        assertEquals(result, copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.utxos().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.transactions().clear());
    }

    @Test
    void deriveRemainsLocalAndDoesNotStartAScan() {
        StubElectrum electrum = new StubElectrum(index -> false);
        AddressInfo address = service(electrum).derive(1, 7);
        assertEquals(1, address.chain);
        assertEquals(7, address.index);
        assertEquals("address:1:7", address.address);
        assertTrue(electrum.calls.isEmpty());
    }

    @Test
    void verifyAddressMatchesReceiveAndChangeAddressesLocally() {
        StubElectrum electrum = new StubElectrum(index -> false);
        WalletService service = service(electrum);

        AddressCheckDto receive = service.verifyAddress("address:0:3");
        assertTrue(receive.belongs());
        assertEquals("address:0:3", receive.address());
        assertEquals(Integer.valueOf(0), receive.chain());
        assertEquals(Integer.valueOf(3), receive.index());
        assertEquals("m/0/3", receive.path());
        assertEquals(service.maxAddresses, receive.checked());

        AddressCheckDto change = service.verifyAddress("  address:1:2  "); // surrounding whitespace is trimmed
        assertTrue(change.belongs());
        assertEquals(Integer.valueOf(1), change.chain());
        assertEquals(Integer.valueOf(2), change.index());

        assertTrue(electrum.calls.isEmpty(), "Verification derives locally and performs no RPC");
    }

    @Test
    void verifyAddressReturnsFalseBeyondTheScanCap() {
        WalletService service = service(new StubElectrum(index -> false));
        AddressCheckDto result = service.verifyAddress("address:0:5"); // maxAddresses is 5, so indices 0..4 only
        assertFalse(result.belongs());
        assertNull(result.chain());
        assertNull(result.index());
        assertNull(result.path());
        assertEquals(5, result.checked());
    }

    @Test
    void verifyAddressRejectsBlankOrOversizedInput() {
        WalletService service = service(new StubElectrum(index -> false));
        for (String invalid : new String[]{null, "", "   "}) {
            assertThrows(BadRequestException.class, () -> service.verifyAddress(invalid));
        }
        assertThrows(BadRequestException.class, () -> service.verifyAddress("x".repeat(129)));
    }

    @Test
    void balanceIncludesNegativeUnconfirmedAmounts() {
        StubElectrum electrum = new StubElectrum(index -> index == 0);
        electrum.unconfirmed = -250L;
        BalanceDto balance = await(service(electrum).scan()).balance();
        assertEquals(new BalanceDto(2_000L, -500L, 1_500L), balance);
    }

    @Test
    void validatesScanConfiguration() {
        StubElectrum electrum = new StubElectrum(index -> false);
        WalletService service = service(electrum);
        for (int invalid : new int[]{0, -1}) {
            service.gapLimit = invalid;
            assertThrows(IllegalArgumentException.class, service::validateConfiguration);
            service.gapLimit = 2;
            service.maxAddresses = invalid;
            assertThrows(IllegalArgumentException.class, service::validateConfiguration);
            service.maxAddresses = 5;
            service.rpcConcurrency = invalid;
            assertThrows(IllegalArgumentException.class, service::validateConfiguration);
            service.rpcConcurrency = 8;
        }
        service.maxAddresses = 1; // A gap larger than the per-chain cap is valid.
        assertDoesNotThrow(service::validateConfiguration);
        assertEquals(0, await(service.scan()).receiveAddress().index());
        assertEquals(2, electrum.scanned.size());
        assertEquals(2, electrum.subscribed.size());
    }

    @Test
    void aCapWithoutTheFullGapOrAnUnscannedReceiveAddressRemainsIncomplete() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum);
        assertFalse(await(service.scan()).discovery().complete());
        electrum.used = index -> false;
        assertFalse(await(service.scan()).discovery().complete());

        WalletService shortScan = service(new StubElectrum(index -> false));
        shortScan.maxAddresses = 1;
        assertFalse(await(shortScan.scan()).discovery().complete());
    }

    @Test
    void internalTransferIncludesReceiveAndChangeInputsAndOutputsDespiteFees() {
        TransactionDto transaction = walletTransaction(800, 1100, 0, false);
        assertEquals(TransactionType.SELF, transaction.type());
        assertEquals(-100, transaction.amount());
    }

    @Test
    void internalTransferWithoutFeesIsSelf() {
        assertEquals(TransactionType.SELF, walletTransaction(2000, 0, 0, false).type());
    }

    @Test
    void largeScanBoundsConcurrentRequestsInEachGroup() {
        ArrayDeque<Runnable> replies = new ArrayDeque<>();
        Map<String, Integer> active = new HashMap<>();
        Map<String, Integer> peaks = new HashMap<>();
        StubElectrum electrum = new StubElectrum(index -> true) {
            @Override public Uni<JsonObject> call(String method, Object... params) {
                Uni<JsonObject> response = super.call(method, params);
                return Uni.createFrom().emitter(emitter -> {
                    int count = active.merge(method, 1, Integer::sum);
                    peaks.merge(method, count, Math::max);
                    replies.add(() -> {
                        active.merge(method, -1, Integer::sum);
                        response.subscribe().with(emitter::complete, emitter::fail);
                    });
                });
            }
        };
        for (int index = 0; index < 30; index++) {
            Transaction transaction = new Transaction(MainNetParams.get());
            transaction.addInput(Sha256Hash.ZERO_HASH, index, new Script(new byte[]{0x51}));
            transaction.addOutput(Coin.valueOf(2000 + index), new Script(new byte[]{0x51}));
            electrum.transactions.add(transaction);
        }
        WalletService service = service(electrum);
        service.maxAddresses = 30;
        service.gapLimit = 20;
        service.rpcConcurrency = 2;
        CompletableFuture<WalletSnapshot> result = service.scan().subscribeAsCompletionStage().toCompletableFuture();
        assertFalse(result.isDone());
        int processed = 0;
        while (!result.isDone() && processed++ < 1000) {
            assertFalse(replies.isEmpty());
            replies.remove().run();
        }
        assertTrue(result.isDone());
        assertEquals(31, result.join().transactions().size());
        assertEquals(2, peaks.get("blockchain.transaction.get"));
        assertEquals(2, peaks.get("blockchain.scripthash.get_balance"));
        assertEquals(2, peaks.get("blockchain.scripthash.listunspent"));
        assertTrue(peaks.get("blockchain.scripthash.subscribe") <= 4);
        assertTrue(peaks.get("blockchain.scripthash.get_history") <= 4);
        assertTrue(active.values().stream().allMatch(count -> count == 0));
    }

    @Test
    void invalidTransactionResponsesNeverPoisonTheScanCache() {
        for (String corruption : List.of("wrong-id", "trailing-bytes", "invalid-hex")) {
            StubElectrum electrum = new StubElectrum(index -> index == 0);
            WalletService service = service(electrum);
            Transaction other = new Transaction(MainNetParams.get());
            other.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[]{0x51}));
            other.addOutput(Coin.valueOf(2000), new Script(new byte[]{0x51}));
            electrum.transactionHexOverride = switch (corruption) {
                case "wrong-id" -> ByteUtils.formatHex(other.bitcoinSerialize());
                case "trailing-bytes" -> ByteUtils.formatHex(electrum.transaction.bitcoinSerialize()) + "00";
                default -> "not-hex";
            };
            assertThrows(RuntimeException.class, () -> await(service.scan()));
            electrum.transactionHexOverride = null;
            assertEquals(1, await(service.scan()).transactions().size());
            assertEquals(2, electrum.calls.stream().filter(call -> call.startsWith("blockchain.transaction.get")).count());
            await(service.scan());
            assertEquals(2, electrum.calls.stream().filter(call -> call.startsWith("blockchain.transaction.get")).count());
        }
    }

    @Test
    void externalPaymentWithChangeIsSent() {
        assertEquals(TransactionType.SENT, walletTransaction(0, 900, 1000, false).type());
    }

    @Test
    void externalInputPreventsSelfClassificationEvenWithZeroNet() {
        assertEquals(TransactionType.RECEIVED, walletTransaction(2000, 0, 0, true).type());
    }

    @Test
    void externalOutputPreventsSelfClassificationEvenWithZeroNet() {
        assertEquals(TransactionType.SENT, walletTransaction(2000, 0, 500, true).type());
    }

    private static TransactionDto walletTransaction(long receiveValue, long changeValue,
                                                     long externalValue, boolean externalInput) {
        StubElectrum electrum = new StubElectrum(index -> index == 0);
        electrum.transaction.addOutput(Coin.valueOf(1000), new Script(new byte[]{0x52}));
        Transaction spending = new Transaction(MainNetParams.get());
        spending.addInput(electrum.transaction.getOutput(0));
        spending.addInput(electrum.transaction.getOutput(1));
        if (externalInput) {
            spending.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[]{0x51}));
        }
        if (receiveValue > 0) {
            spending.addOutput(Coin.valueOf(receiveValue), new Script(new byte[]{0x51}));
        }
        if (changeValue > 0) {
            spending.addOutput(Coin.valueOf(changeValue), new Script(new byte[]{0x52}));
        }
        if (externalValue > 0) {
            spending.addOutput(Coin.valueOf(externalValue), new Script(new byte[]{0x53}));
        }
        electrum.transactions.add(spending);
        return await(service(electrum).scan()).transactions().stream()
                .filter(transaction -> transaction.txid().equals(spending.getTxId().toString()))
                .findFirst().orElseThrow();
    }

    private static WalletSnapshot await(Uni<WalletSnapshot> scan) {
        return scan.await().atMost(Duration.ofSeconds(5));
    }

    private static void assertSubscriptionsBeforeHistory(StubElectrum electrum) {
        for (String hash : electrum.scanned) {
            int subscribed = electrum.calls.indexOf("blockchain.scripthash.subscribe " + hash);
            int history = electrum.calls.indexOf("blockchain.scripthash.get_history " + hash);
            assertTrue(subscribed >= 0 && subscribed < history, hash);
        }
        assertEquals(electrum.subscribed.size(), electrum.subscribed.stream().distinct().count());
        assertEquals(electrum.scanned.size(), electrum.scanned.stream().distinct().count());
    }

    private static WalletService service(StubElectrum electrum) {
        WalletService service = new WalletService();
        service.electrum = electrum;
        service.wallet = new HdWallet() {
            private final Map<String, AddressInfo> addresses = new HashMap<>();

            @Override
            public AddressInfo address(int chain, int index) {
                String id = chain + ":" + index;
                return addresses.computeIfAbsent(id,
                        ignored -> new AddressInfo(chain, index, "address:" + id, id,
                            chain == 0 ? "51" : "52", "m/" + chain + "/" + index));
            }

            @Override
            public NetworkParameters params() {
                return MainNetParams.get();
            }
        };
        service.gapLimit = 2;
        service.maxAddresses = 5;
        service.validateConfiguration();
        return service;
    }

    private static class StubElectrum extends ElectrumClient {
        private IntPredicate used;
        private String failMethod;
        private String failHash;
        private long unconfirmed;
        private String transactionHexOverride;
        private Consumer<String> beforeSubscribe = ignored -> { };
        private final List<String> calls = new ArrayList<>();
        private final List<String> subscribed = new ArrayList<>();
        private final List<String> scanned = new ArrayList<>();
        private final Transaction transaction;
        private final List<Transaction> transactions = new ArrayList<>();

        StubElectrum(IntPredicate used) {
            this.used = used;
            transaction = new Transaction(MainNetParams.get());
            transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[]{0x51}));
            transaction.addOutput(Coin.valueOf(1000), new Script(new byte[]{0x51}));
            transactions.add(transaction);
        }

        void resetCalls() {
            calls.clear();
            subscribed.clear();
            scanned.clear();
        }

        @Override
        public Uni<JsonObject> call(String method, Object... params) {
            if (method.equals("blockchain.scripthash.subscribe")) {
                beforeSubscribe.accept((String) params[0]);
            }
            return Uni.createFrom().item(() -> {
                if (method.startsWith("blockchain.scripthash.")) {
                    assertEquals(1, params.length);
                    assertInstanceOf(String.class, params[0]);
                    // Deliberately fake hashes: no address, path or key may be transmitted.
                    assertTrue(((String) params[0]).matches("[01]:\\d+"));
                }
                calls.add(method + (params.length == 0 ? "" : " " + params[0]));
                if (method.equals(failMethod) && (failHash == null || failHash.equals(params[0]))) {
                    throw new IllegalStateException("Simulated RPC failure");
                }
                Object result = switch (method) {
                    case "blockchain.headers.subscribe" -> new JsonObject().put("height", 100);
                    case "blockchain.scripthash.subscribe" -> {
                        subscribed.add((String) params[0]);
                        yield null; // Null status is valid for an unused script.
                    }
                    case "blockchain.scripthash.get_history" -> {
                        String id = (String) params[0];
                        assertTrue(subscribed.contains(id), "History must wait for subscribe response: " + id);
                        scanned.add(id);
                        int index = Integer.parseInt(id.split(":")[1]);
                        yield used.test(index) ? new JsonArray(transactions.stream()
                            .map(transaction -> new JsonObject()
                                .put("tx_hash", transaction.getTxId().toString()).put("height", 0))
                            .toList()) : new JsonArray();
                    }
                        case "blockchain.scripthash.get_balance" -> new JsonObject()
                            .put("confirmed", 1000L).put("unconfirmed", unconfirmed);
                    case "blockchain.scripthash.listunspent" -> new JsonArray().add(new JsonObject()
                            .put("tx_hash", transaction.getTxId().toString()).put("tx_pos", 0)
                            .put("value", 1000L).put("height", 0));
                        case "blockchain.transaction.get" -> transactionHexOverride != null ? transactionHexOverride : transactions.stream()
                            .filter(transaction -> transaction.getTxId().toString().equals(params[0]))
                            .map(transaction -> ByteUtils.formatHex(transaction.bitcoinSerialize()))
                            .findFirst().orElseThrow();
                    default -> throw new AssertionError("Unexpected RPC: " + method);
                };
                return new JsonObject().put("result", result);
            });
        }
    }
}