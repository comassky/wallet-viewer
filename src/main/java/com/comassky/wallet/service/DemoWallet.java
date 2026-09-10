package com.comassky.wallet.service;

import com.comassky.wallet.derivation.HdWallet;
import com.comassky.wallet.model.AddressInfo;
import com.comassky.wallet.model.BalanceDto;
import com.comassky.wallet.model.ReceiveAddressDto;
import com.comassky.wallet.model.TransactionDetailsDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.TransactionType;
import com.comassky.wallet.model.UtxoDto;
import com.comassky.wallet.model.WalletSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@ApplicationScoped
public class DemoWallet {
    static final int BASE_TIP = 940_000;
    static final int MAX_TRANSACTIONS = 200;

    @Inject HdWallet wallet;
    @Inject Clock clock = Clock.systemUTC();

    private final Random random = new Random(20260909L);
    private final List<UtxoDto> utxos = new ArrayList<>();
    private final LinkedList<TransactionDto> transactions = new LinkedList<>();
    private final Map<String, TransactionDetailsDto> details = new LinkedHashMap<>();
    private final Map<String, AddressInfo> owned = new LinkedHashMap<>();
    private int nextReceiveIndex;
    private int nextChangeIndex;
    private int tip = BASE_TIP;
    private int ticks;
    private WalletSnapshot snapshot;

    public synchronized WalletSnapshot snapshot() {
        ensureBuilt();
        return snapshot;
    }

    public synchronized TransactionDetailsDto details(String txid) {
        ensureBuilt();
        return details.get(txid);
    }

    public synchronized WalletSnapshot tick() {
        ensureBuilt();
        int phase = ++ticks % 4;
        if (phase == 0) mineBlock();
        else if (transactions.size() < MAX_TRANSACTIONS) {
            switch (phase) {
                case 1 -> receive(0, null);
                case 2 -> spend(false, 0, null);
                case 3 -> spend(true, 0, null);
                default -> throw new IllegalStateException("Unknown demo phase");
            }
        }
        snapshot = buildSnapshot();
        return snapshot;
    }

    private void ensureBuilt() {
        if (snapshot != null) return;
        long now = clock.instant().getEpochSecond();
        for (int index = 0; index < 36; index++) {
            int height = index < 33 ? BASE_TIP - 12_960 + index * 390 : BASE_TIP - (35 - index) * 3;
            long timestamp = now - (BASE_TIP - height) * 600L;
            if (index % 3 == 0) receive(height, timestamp);
            else spend(index % 3 == 2, height, timestamp);
        }
        receive(0, null);
        spend(false, 0, null);
        snapshot = buildSnapshot();
    }

    private void receive(int height, Long timestamp) {
        long fee = 300L + random.nextInt(3_000);
        int count = 1 + random.nextInt(3);
        List<TransactionDetailsDto.Output> outputs = new ArrayList<>();
        long received = 0;
        for (int index = 0; index < count; index++) {
            long value = 50_000L + random.nextInt(2_000_000);
            received += value;
            outputs.add(output(index, ownAddress(0), value));
        }
        AddressInfo sender = counterparty();
        long change = 10_000L + random.nextInt(100_000);
        outputs.add(output(outputs.size(), sender, change));
        long total = received + change + fee;
        List<TransactionDetailsDto.Input> inputs = new ArrayList<>();
        int inputCount = 1 + random.nextInt(3);
        long remaining = total;
        for (int index = 0; index < inputCount; index++) {
            long value = index == inputCount - 1 ? remaining : total / inputCount;
            remaining -= value;
            Transaction funding = new Transaction(MainNetParams.get());
            funding.addInput(Sha256Hash.wrap(randomBytes(32)), 0, new Script(new byte[]{0x51}));
            funding.addOutput(Coin.valueOf(value), new Script(HexFormat.of().parseHex(sender.scriptHex())));
            inputs.add(new TransactionDetailsDto.Input(funding.getTxId().toString(), 0, sender.address(), value, false));
        }
        record(inputs, outputs, received, 0, TransactionType.RECEIVED, height, timestamp);
    }

    private void spend(boolean internal, int height, Long timestamp) {
        List<UtxoDto> available = utxos.stream().filter(output -> output.height() > 0 && output.value() > 10_000)
                .sorted(Comparator.comparingLong(UtxoDto::value).reversed())
                .limit(internal ? 3 : 1).toList();
        if (available.isEmpty()) {
            receive(height, timestamp);
            return;
        }
        long total = available.stream().mapToLong(UtxoDto::value).sum();
        long fee = (110L + available.size() * 68L) * (2 + random.nextInt(15));
        List<TransactionDetailsDto.Input> inputs = available.stream()
                .map(output -> new TransactionDetailsDto.Input(output.txid(), output.vout(), output.address(), output.value(), false))
                .toList();
        List<TransactionDetailsDto.Output> outputs = new ArrayList<>();
        long received;
        if (internal) {
            received = total - fee;
            outputs.add(output(0, ownAddress(1), received));
        } else {
            long payment = (total - fee) / 3;
            int recipients = 1 + random.nextInt(2);
            long remainder = payment;
            for (int index = 0; index < recipients; index++) {
                long value = index == recipients - 1 ? remainder : payment / recipients;
                remainder -= value;
                outputs.add(output(index, counterparty(), value));
            }
            received = total - fee - payment;
            outputs.add(output(outputs.size(), ownAddress(1), received));
        }
        utxos.removeAll(available);
        record(inputs, outputs, received, total, internal ? TransactionType.SELF : TransactionType.SENT, height, timestamp);
    }

    private void record(List<TransactionDetailsDto.Input> inputs, List<TransactionDetailsDto.Output> outputs,
                        long received, long sent, TransactionType type, int height, Long timestamp) {
        Transaction transaction = new Transaction(MainNetParams.get());
        transaction.setVersion(2);
        for (TransactionDetailsDto.Input input : inputs) {
            transaction.addInput(Sha256Hash.wrap(input.txid()), input.vout(), new Script(new byte[0]));
        }
        for (TransactionDetailsDto.Output output : outputs) {
            transaction.addOutput(Coin.valueOf(output.value()), new Script(HexFormat.of().parseHex(output.scriptHex())));
        }
        String txid = transaction.getTxId().toString();
        long totalInput = inputs.stream().mapToLong(TransactionDetailsDto.Input::value).sum();
        long totalOutput = outputs.stream().mapToLong(TransactionDetailsDto.Output::value).sum();
        details.put(txid, new TransactionDetailsDto(txid, 2, 0, transaction.bitcoinSerialize().length,
                inputs, outputs, totalInput, totalOutput, totalInput - totalOutput));
        int confirmations = height > 0 ? tip - height + 1 : 0;
        transactions.addFirst(new TransactionDto(txid, received - sent, received, sent,
                height, confirmations, timestamp, type));
        for (TransactionDetailsDto.Output output : outputs) {
            if (owned.containsKey(output.address())) {
                utxos.add(new UtxoDto(txid, output.index(), output.value(), height, confirmations, output.address()));
            }
        }
    }

    private void mineBlock() {
        tip++;
        long now = clock.instant().getEpochSecond();
        transactions.replaceAll(transaction -> {
            int height = transaction.height() > 0 ? transaction.height() : tip;
            return new TransactionDto(transaction.txid(), transaction.amount(), transaction.received(), transaction.sent(),
                    height, tip - height + 1, transaction.timestamp() == null ? now : transaction.timestamp(), transaction.type());
        });
        utxos.replaceAll(output -> {
            int height = output.height() > 0 ? output.height() : tip;
            return new UtxoDto(output.txid(), output.vout(), output.value(), height, tip - height + 1, output.address());
        });
    }

    private WalletSnapshot buildSnapshot() {
        long confirmed = transactions.stream().filter(transaction -> transaction.height() > 0).mapToLong(TransactionDto::amount).sum();
        long unconfirmed = transactions.stream().filter(transaction -> transaction.height() == 0).mapToLong(TransactionDto::amount).sum();
        AddressInfo next = wallet.address(0, nextReceiveIndex);
        return new WalletSnapshot(new BalanceDto(confirmed, unconfirmed), List.copyOf(utxos), List.copyOf(transactions),
                new ReceiveAddressDto(next.index(), next.address(), next.path()));
    }

    private AddressInfo ownAddress(int chain) {
        AddressInfo address = wallet.address(chain, chain == 0 ? nextReceiveIndex++ : nextChangeIndex++);
        owned.put(address.address(), address);
        return address;
    }

    private AddressInfo counterparty() {
        var network = MainNetParams.get();
        var address = switch (random.nextInt(3)) {
            case 0 -> LegacyAddress.fromPubKeyHash(network, randomBytes(20));
            case 1 -> SegwitAddress.fromProgram(network, 0, randomBytes(20));
            default -> SegwitAddress.fromProgram(network, 1, randomBytes(32));
        };
        return new AddressInfo(0, 0, address.toString(), "",
                HexFormat.of().formatHex(ScriptBuilder.createOutputScript(address).getProgram()), "");
    }

    private static TransactionDetailsDto.Output output(int index, AddressInfo address, long value) {
        return new TransactionDetailsDto.Output(index, address.address(), value, address.scriptHex());
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}