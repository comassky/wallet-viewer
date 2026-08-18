package com.example.walletviewer;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {

    @Test
    void scansAreLazyAndRespectANonMultipleAddressLimit() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum, 0);
        Uni<WalletSnapshot> snapshot = service.snapshot();
        assertTrue(electrum.scanned.isEmpty());

        WalletSnapshot result = snapshot.await().atMost(Duration.ofSeconds(5));
        assertEquals(5, result.receiveAddress().index());
        assertEquals(10, electrum.scanned.size()); // Five addresses per chain, not six.
        assertFalse(electrum.scanned.contains("0:5"));
        assertFalse(electrum.scanned.contains("1:5"));

        electrum.scanned.clear();
        snapshot.await().atMost(Duration.ofSeconds(5));
        assertEquals(10, electrum.scanned.size()); // Fresh accumulator for each subscription.
    }

    @Test
    void cachesSnapshotsWithinTtl() {
        StubElectrum electrum = new StubElectrum(index -> false);
        WalletService service = service(electrum, 30);
        WalletSnapshot first = service.snapshot().await().atMost(Duration.ofSeconds(5));
        WalletSnapshot second = service.snapshot().await().atMost(Duration.ofSeconds(5));
        assertSame(first, second);
        assertEquals(4, electrum.scanned.size());
        assertEquals(0, first.receiveAddress().index());
    }

    @Test
    void expiredCacheRebuildsTheWholeScan() {
        StubElectrum electrum = new StubElectrum(index -> true);
        WalletService service = service(electrum, 1);
        Uni<WalletSnapshot> snapshot = service.snapshot();
        WalletSnapshot first = snapshot.await().atMost(Duration.ofSeconds(5));
        electrum.scanned.clear();

        Uni.createFrom().voidItem().onItem().delayIt().by(Duration.ofMillis(1100))
                .await().atMost(Duration.ofSeconds(5));
        WalletSnapshot refreshed = snapshot.await().atMost(Duration.ofSeconds(5));
        assertNotSame(first, refreshed);
        assertEquals(10, electrum.scanned.size());
        assertEquals(5, refreshed.receiveAddress().index());
    }

    @Test
    void receiveAddressFollowsLastUsedAddressInsteadOfReusingAHole() {
        WalletService service = service(new StubElectrum(index -> index == 1), 0);
        WalletSnapshot result = service.snapshot().await().atMost(Duration.ofSeconds(5));
        assertEquals(2, result.receiveAddress().index());
    }

    @Test
    void validatesScanConfiguration() {
        WalletService service = service(new StubElectrum(index -> false), 0);
        service.gapLimit = 0;
        assertThrows(IllegalArgumentException.class, service::validateConfiguration);
        service.gapLimit = 2;
        service.maxAddresses = 0;
        assertThrows(IllegalArgumentException.class, service::validateConfiguration);
        service.maxAddresses = 5;
        service.cacheTtl = -1;
        assertThrows(IllegalArgumentException.class, service::validateConfiguration);
    }

    private static WalletService service(StubElectrum electrum, int ttl) {
        WalletService service = new WalletService();
        service.electrum = electrum;
        service.wallet = new HdWallet() {
            @Override
            public AddressInfo address(int chain, int index) {
                String id = chain + ":" + index;
                return new AddressInfo(chain, index, id, id, "51", "m/" + chain + "/" + index);
            }

            @Override
            public NetworkParameters params() {
                return MainNetParams.get();
            }
        };
        service.gapLimit = 2;
        service.maxAddresses = 5;
        service.cacheTtl = ttl;
        service.validateConfiguration();
        return service;
    }

    private static class StubElectrum extends ElectrumClient {
        private final IntPredicate used;
        private final List<String> scanned = new ArrayList<>();
        private final Transaction transaction;

        StubElectrum(IntPredicate used) {
            this.used = used;
            transaction = new Transaction(MainNetParams.get());
            transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[]{0x51}));
            transaction.addOutput(Coin.valueOf(1000), new Script(new byte[]{0x51}));
        }

        @Override
        public Uni<JsonObject> call(String method, Object... params) {
            return Uni.createFrom().item(() -> {
                Object result = switch (method) {
                    case "blockchain.headers.subscribe" -> new JsonObject().put("height", 100);
                    case "blockchain.scripthash.get_history" -> {
                        String id = (String) params[0];
                        scanned.add(id);
                        int index = Integer.parseInt(id.split(":")[1]);
                        yield used.test(index) ? new JsonArray().add(new JsonObject()
                                .put("tx_hash", transaction.getTxId().toString()).put("height", 0)) : new JsonArray();
                    }
                    case "blockchain.scripthash.get_balance" -> new JsonObject();
                    case "blockchain.scripthash.listunspent" -> new JsonArray();
                    case "blockchain.transaction.get" -> Utils.HEX.encode(transaction.bitcoinSerialize());
                    default -> throw new AssertionError("Unexpected RPC: " + method);
                };
                return new JsonObject().put("result", result);
            });
        }
    }
}