package com.comassky.wallet.service;

import com.comassky.wallet.derivation.HdWallet;
import com.comassky.wallet.model.AddressInfo;
import com.comassky.wallet.model.TransactionDetailsDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.TransactionType;
import com.comassky.wallet.model.UtxoDto;
import com.comassky.wallet.model.WalletSnapshot;
import jakarta.enterprise.inject.Vetoed;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DemoWalletTest {
    private DemoWallet simulation() {
        DemoWallet simulation = new DemoWallet();
        simulation.wallet = new DerivedWallet();
        simulation.clock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC);
        return simulation;
    }

    @Test
    void initialHistoryIsDiverseDeterministicAndInternallyConsistent() {
        DemoWallet simulation = simulation();
        WalletSnapshot snapshot = simulation.snapshot();
        assertEquals(snapshot, simulation().snapshot());
        assertEquals(38, snapshot.transactions().size());
        assertEquals(Set.of(TransactionType.RECEIVED, TransactionType.SENT, TransactionType.SELF),
                snapshot.transactions().stream().map(TransactionDto::type).collect(Collectors.toSet()));
        assertEquals(2, snapshot.transactions().stream().filter(transaction -> transaction.height() == 0).count());
        assertTrue(snapshot.transactions().stream().anyMatch(transaction -> transaction.confirmations() == 1));
        assertTrue(snapshot.transactions().stream().anyMatch(transaction -> transaction.confirmations() == 4));
        assertTrue(snapshot.transactions().stream().anyMatch(transaction -> transaction.confirmations() == 7));
        assertConsistent(simulation, snapshot);
        for (TransactionDto summary : snapshot.transactions()) {
            TransactionDetailsDto details = simulation.details(summary.txid());
            Transaction transaction = new Transaction(MainNetParams.get());
            transaction.setVersion(2);
            details.inputs().forEach(input -> transaction.addInput(Sha256Hash.wrap(input.txid()), input.vout(), new Script(new byte[0])));
            details.outputs().forEach(output -> transaction.addOutput(Coin.valueOf(output.value()),
                    new Script(HexFormat.of().parseHex(output.scriptHex()))));
            assertEquals(details.txid(), transaction.getTxId().toString());
            assertEquals(details.size(), transaction.bitcoinSerialize().length);
            details.outputs().forEach(output -> assertEquals(output.address(),
                    new Script(HexFormat.of().parseHex(output.scriptHex())).getToAddress(MainNetParams.get()).toString()));
        }
    }

    @Test
    void mempoolAndBlocksHaveSeparateLifecyclesAndSnapshotsRemainImmutable() {
        DemoWallet simulation = simulation();
        WalletSnapshot initial = simulation.snapshot();
        String pendingId = initial.transactions().getFirst().txid();
        for (int tick = 1; tick <= 3; tick++) {
            WalletSnapshot pending = simulation.tick();
            assertEquals(38 + tick, pending.transactions().size());
            assertEquals(0, pending.transactions().getFirst().confirmations());
            assertNull(pending.transactions().getFirst().timestamp());
            assertConsistent(simulation, pending);
        }
        WalletSnapshot confirmed = simulation.tick();
        assertEquals(41, confirmed.transactions().size());
        assertEquals(0, confirmed.balance().unconfirmed());
        TransactionDto transaction = confirmed.transactions().stream().filter(item -> item.txid().equals(pendingId)).findFirst().orElseThrow();
        assertEquals(1, transaction.confirmations());
        assertEquals(simulation.clock.instant().getEpochSecond(), transaction.timestamp());
        for (int tick = 0; tick < 4; tick++) simulation.tick();
        assertEquals(2, simulation.snapshot().transactions().stream().filter(item -> item.txid().equals(pendingId))
                .findFirst().orElseThrow().confirmations());
        assertEquals(0, initial.transactions().getFirst().confirmations());
        assertThrows(UnsupportedOperationException.class, () -> initial.utxos().clear());
        assertConsistent(simulation, simulation.snapshot());
    }

    @Test
    void longRunningDemoStaysBoundedWithoutDiscardingItsAccountingHistory() {
        DemoWallet simulation = simulation();
        for (int tick = 0; tick < 240; tick++) assertConsistent(simulation, simulation.tick());
        assertEquals(DemoWallet.MAX_TRANSACTIONS, simulation.snapshot().transactions().size());
        assertEquals(0, simulation.snapshot().balance().unconfirmed());
        assertNull(simulation.details("missing"));
    }

    private void assertConsistent(DemoWallet simulation, WalletSnapshot snapshot) {
        assertEquals(snapshot.balance().total(), snapshot.utxos().stream().mapToLong(UtxoDto::value).sum());
        assertEquals(snapshot.balance().total(), snapshot.transactions().stream().mapToLong(TransactionDto::amount).sum());
        Set<String> spent = new HashSet<>();
        for (TransactionDto summary : snapshot.transactions()) {
            TransactionDetailsDto details = simulation.details(summary.txid());
            assertEquals(details.totalInput() - details.totalOutput(), details.fee());
            assertTrue(details.fee() > 0);
            assertEquals(summary.received() - summary.sent(), summary.amount());
            if (summary.type() == TransactionType.SELF) assertEquals(-details.fee(), summary.amount());
            for (TransactionDetailsDto.Input input : details.inputs()) {
                assertTrue(spent.add(input.txid() + ":" + input.vout()), "No double spend");
                TransactionDetailsDto parent = simulation.details(input.txid());
                if (parent != null) {
                    var output = parent.outputs().get((int) input.vout());
                    assertEquals(output.value(), input.value());
                    assertEquals(output.address(), input.address());
                }
            }
        }
        Set<String> unspent = new HashSet<>();
        for (UtxoDto output : snapshot.utxos()) {
            String outpoint = output.txid() + ":" + output.vout();
            assertFalse(spent.contains(outpoint));
            assertTrue(unspent.add(outpoint));
            assertTrue(output.value() > 0);
            var parent = simulation.details(output.txid()).outputs().get(output.vout());
            assertEquals(parent.value(), output.value());
            assertEquals(parent.address(), output.address());
        }
    }

    @Vetoed
    static class DerivedWallet extends HdWallet {
        @Override
        public AddressInfo address(int chain, int index) {
            byte[] hash = Arrays.copyOf(Sha256Hash.hash((chain + ":" + index).getBytes(StandardCharsets.UTF_8)), 20);
            var address = SegwitAddress.fromProgram(MainNetParams.get(), 0, hash);
            return new AddressInfo(chain, index, address.toString(), "",
                    HexFormat.of().formatHex(ScriptBuilder.createOutputScript(address).getProgram()), "m/" + chain + "/" + index);
        }
    }
}