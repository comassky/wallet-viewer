package com.comassky.wallet.service;

import com.comassky.wallet.derivation.HdWallet;
import com.comassky.wallet.electrum.ElectrumClient;
import com.comassky.wallet.model.BalanceDto;
import com.comassky.wallet.model.TransactionDetailsDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.TransactionType;
import com.comassky.wallet.model.WalletSnapshot;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.inject.Vetoed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class TransactionDetailsServiceTest {
    private static final NetworkParameters PARAMS = MainNetParams.get();
    private static final Script NONSTANDARD = new Script(new byte[]{0x51});
    private final StubElectrum electrum = new StubElectrum();
    private final StubLive live = new StubLive();
    private final TransactionDetailsService service = service();

    @AfterEach
    void stop() {
        live.stop();
    }

    @Test
    void malformedIdsAreRejectedBeforeSnapshotOrRpc() {
        for (String id : new String[]{null, "", "0".repeat(63), "0".repeat(65), "g".repeat(64),
                " " + "0".repeat(64), "0".repeat(64) + "\n"}) {
            assertEquals(400, assertThrows(BadRequestException.class,
                    () -> await(service.details(id))).getResponse().getStatus());
        }
        assertEquals(0, live.calls);
        assertTrue(electrum.calls.isEmpty());
    }

    @Test
    void unknownIdsAndLoadingNeverReachElectrum() {
        String id = "ab".repeat(32);
        live.snapshot = snapshot();
        assertEquals(404, assertThrows(NotFoundException.class,
                () -> await(service.details(id))).getResponse().getStatus());
        live.snapshot = null; // Delegate to the actual initial-loading implementation.
        assertEquals(503, assertThrows(ServiceUnavailableException.class,
                () -> await(service.details(id))).getResponse().getStatus());
        assertEquals(2, live.calls);
        assertTrue(electrum.calls.isEmpty());
    }

    @Test
    void normalizesIdAndComputesTrueFeeFromAllDistinctExternalParents() {
        Script legacy = ScriptBuilder.createOutputScript(LegacyAddress.fromPubKeyHash(PARAMS, new byte[20]));
        Script wrapped = ScriptBuilder.createOutputScript(LegacyAddress.fromScriptHash(PARAMS, new byte[20]));
        Script witness = ScriptBuilder.createOutputScript(SegwitAddress.fromProgram(PARAMS, 0, new byte[20]));
        Transaction first = parent(1, 7_000, 11_000);
        first.clearOutputs();
        first.addOutput(Coin.valueOf(7_000), legacy);
        first.addOutput(Coin.valueOf(11_000), wrapped);
        Transaction second = parent(2, 23_000);
        second.clearOutputs();
        second.addOutput(Coin.valueOf(23_000), witness);
        Transaction tx = spend(first, 0, 39_000);
        tx.addInput(first.getTxId(), 1, NONSTANDARD);
        tx.addInput(second.getTxId(), 0, NONSTANDARD);
        tx.setVersion(2);
        tx.setLockTime(123);
        tx.replaceInput(0, tx.getInput(0).withWitness(TransactionWitness.of(new byte[]{1, 2, 3})));
        authorize(tx);
        electrum.put(first);
        electrum.put(second);

        TransactionDetailsDto dto = await(service.details(tx.getTxId().toString().toUpperCase(Locale.ROOT)));
        assertEquals(tx.getTxId().toString(), dto.txid());
        assertEquals(2L, dto.version());
        assertEquals(123L, dto.lockTime());
        assertEquals(tx.bitcoinSerialize().length, dto.size()); // Includes witness bytes, not vsize.
        assertEquals(Long.valueOf(41_000), dto.totalInput());
        assertEquals(39_000L, dto.totalOutput());
        assertEquals(Long.valueOf(2_000), dto.fee()); // Snapshot net amount is deliberately unrelated.
        assertEquals(List.of(7_000L, 11_000L, 23_000L), dto.inputs().stream().map(TransactionDetailsDto.Input::value).toList());
        assertEquals(List.of(0L, 1L, 0L), dto.inputs().stream().map(TransactionDetailsDto.Input::vout).toList());
        assertEquals(List.of(legacy, wrapped, witness).stream().map(s -> s.getToAddress(PARAMS).toString()).toList(),
                dto.inputs().stream().map(TransactionDetailsDto.Input::address).toList());
        assertTrue(dto.inputs().stream().noneMatch(TransactionDetailsDto.Input::coinbase));
        assertEquals(3, electrum.calls.size());
        assertEquals(1L, electrum.calls.stream().filter(first.getTxId().toString()::equals).count());
        assertEquals(List.of(tx.getTxId().toString()), live.snapshot.transactions().stream().map(TransactionDto::txid).toList());
    }

    @Test
    void coinbaseHasNoInputTotalOrFeeAndDoesNotFetchZeroHash() {
        Transaction tx = parent(3, 5_000_000_000L);
        authorize(tx);
        TransactionDetailsDto dto = await(service.details(tx.getTxId().toString()));
        assertEquals(List.of(new TransactionDetailsDto.Input(null, 0, null, null, true)), dto.inputs());
        assertNull(dto.totalInput());
        assertNull(dto.fee());
        assertEquals(5_000_000_000L, dto.totalOutput());
        assertEquals(List.of(tx.getTxId().toString()), electrum.calls);
    }

    @Test
    void rendersTaprootWitnessScriptHashAndUnsupportedOutputs() {
        String taproot = SegwitAddress.fromProgram(PARAMS, 1, new byte[32]).toString();
        String witness = SegwitAddress.fromProgram(PARAMS, 0, new byte[32]).toString();
        Transaction tx = parent(4, 1);
        tx.clearOutputs();
        tx.addOutput(Coin.valueOf(101), ScriptBuilder.createOutputScript(SegwitAddress.fromProgram(PARAMS, 1, new byte[32])));
        tx.addOutput(Coin.valueOf(202), ScriptBuilder.createOutputScript(SegwitAddress.fromProgram(PARAMS, 0, new byte[32])));
        tx.addOutput(Coin.ZERO, new Script(new byte[]{0x6a}));
        tx.addOutput(Coin.valueOf(303), NONSTANDARD);
        authorize(tx);
        TransactionDetailsDto dto = await(service.details(tx.getTxId().toString()));
        assertTrue(taproot.startsWith("bc1p"));
        assertEquals(taproot, dto.outputs().get(0).address());
        assertEquals(witness, dto.outputs().get(1).address());
        assertNull(dto.outputs().get(2).address());
        assertNull(dto.outputs().get(3).address());
        assertEquals(606L, dto.totalOutput());
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            assertEquals(i, dto.outputs().get(i).index());
            assertEquals(tx.getOutput(i).getValue().value, dto.outputs().get(i).value());
            assertEquals(ByteUtils.formatHex(tx.getOutput(i).getScriptBytes()), dto.outputs().get(i).scriptHex());
        }
    }

    @Test
    void unsupportedPreviousScriptStillResolvesItsValue() {
        Transaction previous = parent(5, 500);
        Transaction tx = spend(previous, 0, 400);
        authorize(tx);
        electrum.put(previous);
        TransactionDetailsDto dto = await(service.details(tx.getTxId().toString()));
        assertNull(dto.inputs().getFirst().address());
        assertEquals(Long.valueOf(500), dto.inputs().getFirst().value());
        assertEquals(Long.valueOf(100), dto.fee());
    }

    @Test
    void corruptedTransactionOrParentHashReturnsSanitizedBadGateway() {
        Transaction previous = parent(6, 500);
        Transaction tx = spend(previous, 0, 400);
        authorize(tx);
        electrum.put(previous);
        electrum.results.put(tx.getTxId().toString(), raw(previous));
        badGateway(tx);
        assertEquals(1, electrum.calls.size());

        electrum.put(tx);
        electrum.results.put(previous.getTxId().toString(), raw(parent(7, 500)));
        badGateway(tx);
    }

    @Test
    void missingParentAndUnsignedOutOfRangeIndexFailRatherThanInventingZero() {
        Transaction previous = parent(8, 500);
        Transaction tx = spend(previous, 0, 400);
        authorize(tx);
        badGateway(tx); // Missing result from parent lookup.
        electrum.put(previous);
        for (long index : new long[]{1, 0xffff_fffeL}) {
            Transaction invalid = spend(previous, index, 400);
            authorize(invalid);
            badGateway(invalid);
        }
    }

    @Test
    void malformedRawResponsesAndTrailingBytesAreSanitized() {
        Transaction tx = parent(9, 500);
        authorize(tx);
        for (Object result : new Object[]{"not raw hex", "0", "00", raw(tx) + "00", new JsonObject()}) {
            electrum.results.put(tx.getTxId().toString(), result);
            badGateway(tx);
        }
    }

    @Test
    void upstreamHttpFailuresAreMappedEvenWhenTheirStatusLooksLikeAuthorization() {
        Transaction tx = parent(10, 500);
        authorize(tx);
        for (RuntimeException failure : List.of(new BadRequestException("private raw data"),
                new NotFoundException("private raw data"), new ServiceUnavailableException("private raw data"))) {
            electrum.failure = failure;
            badGateway(tx);
        }
        electrum.throwSynchronously = true;
        badGateway(tx);
    }

    @Test
    void ancestorRpcConcurrencyIsBoundedAndRequestsAreLazy() throws Exception {
        List<Transaction> parents = new ArrayList<>();
        Transaction tx = new Transaction(PARAMS);
        for (int i = 0; i < 9; i++) {
            Transaction previous = parent(20 + i, 100);
            parents.add(previous);
            tx.addInput(previous.getTxId(), 0, NONSTANDARD);
            electrum.put(previous);
        }
        tx.addOutput(Coin.valueOf(800), NONSTANDARD);
        authorize(tx);
        electrum.immediateId = tx.getTxId().toString();
        electrum.holdAncestors = true;
        Uni<TransactionDetailsDto> details = service.details(tx.getTxId().toString());
        assertTrue(electrum.calls.isEmpty());
        CompletableFuture<TransactionDetailsDto> result = details.subscribeAsCompletionStage();
        assertEquals(8, electrum.pending.size());
        assertEquals(9, electrum.calls.size()); // Requested transaction plus eight ancestors.
        Transaction first = parents.getFirst();
        electrum.pending.get(first.getTxId().toString()).complete(new JsonObject().put("result", raw(first)));
        assertEquals(9, electrum.pending.size()); // Only now may the ninth ancestor start.
        for (Transaction previous : parents) {
            electrum.pending.get(previous.getTxId().toString()).complete(new JsonObject().put("result", raw(previous)));
        }
        assertEquals(Long.valueOf(100), result.get(5, java.util.concurrent.TimeUnit.SECONDS).fee());
    }

    @Test
    void detailsAreCachedButMembershipIsCheckedOnEachSubscription() {
        Transaction tx = parent(40, 500);
        authorize(tx);
        Uni<TransactionDetailsDto> details = service.details(tx.getTxId().toString());
        await(details);
        await(details); // Immutable details are served from the cache without a second fetch.
        assertEquals(1, electrum.calls.size());
        live.snapshot = snapshot();
        assertThrows(NotFoundException.class, () -> await(details)); // Membership is still re-checked before the cache.
        assertEquals(1, electrum.calls.size());
    }

    @Test
    void dtoListsAreDefensiveImmutableCopies() {
        List<TransactionDetailsDto.Input> inputs = new ArrayList<>(List.of(new TransactionDetailsDto.Input(null, 0, null, null, true)));
        List<TransactionDetailsDto.Output> outputs = new ArrayList<>(List.of(new TransactionDetailsDto.Output(0, null, 500, "51")));
        TransactionDetailsDto dto = new TransactionDetailsDto("ab".repeat(32), 1, 0, 60, inputs, outputs, null, 500, null);
        inputs.clear();
        outputs.clear();
        assertEquals(1, dto.inputs().size());
        assertEquals(1, dto.outputs().size());
        assertThrows(UnsupportedOperationException.class, () -> dto.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> dto.outputs().clear());
    }

    private void badGateway(Transaction tx) {
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> await(service.details(tx.getTxId().toString())));
        assertEquals(502, error.getResponse().getStatus());
        assertEquals("Unable to load transaction details", error.getMessage());
        assertNull(error.getCause());
        assertFalse(error.getResponse().hasEntity());
    }

    private void authorize(Transaction tx) {
        live.snapshot = snapshot(tx.getTxId().toString());
        electrum.put(tx);
    }

    private TransactionDetailsService service() {
        TransactionDetailsService result = new TransactionDetailsService();
        result.electrum = electrum;
        result.live = live;
        result.demo = new DemoService();
        result.wallet = new HdWallet() {
            @Override public NetworkParameters params() { return PARAMS; }
        };
        return result;
    }

    private static TransactionDetailsDto await(Uni<TransactionDetailsDto> details) {
        return details.await().atMost(Duration.ofSeconds(5));
    }

    private static WalletSnapshot snapshot(String... ids) {
        List<TransactionDto> transactions = new ArrayList<>();
        for (String id : ids) transactions.add(new TransactionDto(id, -777, 0, 777, 1, 1, null, TransactionType.SENT));
        return new WalletSnapshot(new BalanceDto(0, 0), List.of(), transactions, null);
    }

    private static Transaction parent(int tag, long... values) {
        Transaction tx = new Transaction(PARAMS);
        tx.addInput(Sha256Hash.ZERO_HASH, 0xffff_ffffL, new ScriptBuilder().data(new byte[]{1, (byte) tag}).build());
        for (long value : values) tx.addOutput(Coin.valueOf(value), NONSTANDARD);
        return tx;
    }

    private static Transaction spend(Transaction previous, long index, long output) {
        Transaction tx = new Transaction(PARAMS);
        tx.addInput(previous.getTxId(), index, NONSTANDARD);
        tx.addOutput(Coin.valueOf(output), NONSTANDARD);
        return tx;
    }

    private static String raw(Transaction tx) { return ByteUtils.formatHex(tx.bitcoinSerialize()); }

    // Manually instantiated unit-test stub, never a CDI bean in @QuarkusTest.
    @Vetoed
    private static class StubLive extends WalletLiveService {
        WalletSnapshot snapshot;
        int calls;

        @Override public Uni<WalletSnapshot> snapshot() {
            calls++;
            return Uni.createFrom().deferred(() -> snapshot == null ? super.snapshot() : Uni.createFrom().item(snapshot));
        }
    }

    private static class StubElectrum extends ElectrumClient {
        final Map<String, Object> results = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final Map<String, CompletableFuture<JsonObject>> pending = new LinkedHashMap<>();
        RuntimeException failure;
        boolean throwSynchronously;
        boolean holdAncestors;
        String immediateId;

        void put(Transaction tx) { results.put(tx.getTxId().toString(), raw(tx)); }

        @Override public Uni<JsonObject> call(String method, Object... params) {
            assertEquals("blockchain.transaction.get", method);
            assertEquals(1, params.length); // Raw (non-verbose) Electrum request.
            String id = (String) params[0];
            calls.add(id);
            if (throwSynchronously) throw failure;
            if (failure != null) return Uni.createFrom().failure(failure);
            if (holdAncestors && !id.equals(immediateId)) {
                CompletableFuture<JsonObject> response = new CompletableFuture<>();
                pending.put(id, response);
                return Uni.createFrom().completionStage(response);
            }
            return Uni.createFrom().item(new JsonObject().put("result", results.get(id)));
        }
    }
}