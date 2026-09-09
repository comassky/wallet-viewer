package com.example.walletviewer.service;

import com.example.walletviewer.derivation.HdWallet;
import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.AddressInfo;
import com.example.walletviewer.model.BalanceDto;
import com.example.walletviewer.model.TransactionDto;
import com.example.walletviewer.model.UtxoDto;
import com.example.walletviewer.model.WalletSnapshot;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
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
        WalletSnapshot copy = new WalletSnapshot(result.balance(), utxos, transactions, result.receiveAddress());
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
        }
        service.maxAddresses = 1; // A gap larger than the per-chain cap is valid.
        assertDoesNotThrow(service::validateConfiguration);
        assertEquals(0, await(service.scan()).receiveAddress().index());
        assertEquals(2, electrum.scanned.size());
        assertEquals(2, electrum.subscribed.size());
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
                        ignored -> new AddressInfo(chain, index, "address:" + id, id, "51", "m/" + chain + "/" + index));
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
        private Consumer<String> beforeSubscribe = ignored -> { };
        private final List<String> calls = new ArrayList<>();
        private final List<String> subscribed = new ArrayList<>();
        private final List<String> scanned = new ArrayList<>();
        private final Transaction transaction;

        StubElectrum(IntPredicate used) {
            this.used = used;
            transaction = new Transaction(MainNetParams.get());
            transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[]{0x51}));
            transaction.addOutput(Coin.valueOf(1000), new Script(new byte[]{0x51}));
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
                        yield used.test(index) ? new JsonArray().add(new JsonObject()
                                .put("tx_hash", transaction.getTxId().toString()).put("height", 0)) : new JsonArray();
                    }
                        case "blockchain.scripthash.get_balance" -> new JsonObject()
                            .put("confirmed", 1000L).put("unconfirmed", unconfirmed);
                    case "blockchain.scripthash.listunspent" -> new JsonArray().add(new JsonObject()
                            .put("tx_hash", transaction.getTxId().toString()).put("tx_pos", 0)
                            .put("value", 1000L).put("height", 0));
                    case "blockchain.transaction.get" -> ByteUtils.formatHex(transaction.bitcoinSerialize());
                    default -> throw new AssertionError("Unexpected RPC: " + method);
                };
                return new JsonObject().put("result", result);
            });
        }
    }
}