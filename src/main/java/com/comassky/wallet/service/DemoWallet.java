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

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Self-consistent synthetic wallet used only when {@code wallet.demo=true}, so the UI can be
 * demonstrated or screenshotted without a real xpub, address or transaction. It never touches
 * Electrum: the coordinator seeds this snapshot and, every 15 seconds, {@link #tick()} emits a
 * random incoming or outgoing transaction so the live stream keeps updating. Our own outputs use
 * addresses derived from the (demo) key; counterparties are public example addresses.
 */
@ApplicationScoped
public class DemoWallet {
    // Public BIP-173/-350 example addresses, used only as fictitious counterparties.
    private static final String EXT1 = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
    private static final String EXT2 = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh";
    private static final String EXT3 = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
    private static final String EXT4 = "bc1q34aq5drpuwy3wgl9lhup9892qp6svr8ldzyy7c";
    private static final String EXT5 = "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3";
    private static final String[] EXT = {EXT1, EXT2, EXT3, EXT4, EXT5};
    private static final String EXT_SCRIPT = "00140102030405060708090a0b0c0d0e0f1011121314";

    private static final String TX0 = "a0".repeat(32);
    private static final String TX1 = "b1".repeat(32);
    private static final String TX2 = "c2".repeat(32);
    private static final String TX3 = "d3".repeat(32);
    private static final String PREV0 = "e4".repeat(32);
    private static final String PREV1A = "f5".repeat(32);
    private static final String PREV1B = "06".repeat(32);
    private static final String PREV1C = "17".repeat(32);
    private static final String PREV1D = "28".repeat(32);
    private static final String PREV2 = "39".repeat(32);

    private static final int BASE_TIP = 940_000;
    private static final int MAX_HISTORY = 25;
    private static final int MAX_UTXOS = 9;

    @Inject HdWallet wallet;

    private final Random random = new Random(20260909L);
    private List<UtxoDto> utxos;
    private LinkedList<TransactionDto> txs;
    private Map<String, TransactionDetailsDto> details;
    private long confirmed;
    private long unconfirmed;
    private int nextReceiveIndex;
    private int tip;
    private WalletSnapshot snapshot;

    public synchronized WalletSnapshot snapshot() {
        ensureBuilt();
        return snapshot;
    }

    /** Emits one random incoming or outgoing transaction and returns the updated snapshot. */
    public synchronized WalletSnapshot tick() {
        ensureBuilt();
        emitRandomTransaction();
        snapshot = buildSnapshot();
        return snapshot;
    }

    public synchronized TransactionDetailsDto details(String txid) {
        ensureBuilt();
        return details.get(txid);
    }

    private void ensureBuilt() {
        if (snapshot != null) {
            return;
        }
        AddressInfo r0 = wallet.address(0, 0);
        AddressInfo r1 = wallet.address(0, 1);
        AddressInfo r2 = wallet.address(0, 2);
        AddressInfo r3 = wallet.address(0, 3);
        AddressInfo r4 = wallet.address(0, 4); // funded by TX0, spent by TX3
        AddressInfo c0 = wallet.address(1, 0);

        int conf0 = BASE_TIP - 939_000 + 1;
        int conf1 = BASE_TIP - 939_500 + 1;
        int conf3 = BASE_TIP - 939_800 + 1;

        utxos = new ArrayList<>(List.of(
                new UtxoDto(TX1, 0, 1_000_000, 939_500, conf1, r0.address),
                new UtxoDto(TX1, 1, 5_000_000, 939_500, conf1, r1.address),
                new UtxoDto(TX1, 2, 2_000_000, 939_500, conf1, r2.address),
                new UtxoDto(TX3, 1, 95_000, 939_800, conf3, c0.address),
                new UtxoDto(TX2, 0, 115_722, 0, 0, r3.address))); // pending
        confirmed = 8_095_000L;
        unconfirmed = 115_722L;

        txs = new LinkedList<>(List.of(
                new TransactionDto(TX2, 115_722, 115_722, 0, 0, 0, null, TransactionType.RECEIVED),
                new TransactionDto(TX3, -405_000, 95_000, 500_000, 939_800, conf3, 1_784_800_000L, TransactionType.SENT),
                new TransactionDto(TX1, 8_000_000, 8_000_000, 0, 939_500, conf1, 1_784_500_000L, TransactionType.RECEIVED),
                new TransactionDto(TX0, 500_000, 500_000, 0, 939_000, conf0, 1_784_000_000L, TransactionType.RECEIVED)));

        details = new LinkedHashMap<>();
        details.put(TX0, new TransactionDetailsDto(TX0, 2, 0, 205,
                List.of(new TransactionDetailsDto.Input(PREV0, 0, EXT5, 505_000L, false)),
                List.of(new TransactionDetailsDto.Output(0, r4.address, 500_000, r4.scriptHex),
                        new TransactionDetailsDto.Output(1, EXT1, 4_000, EXT_SCRIPT)),
                505_000L, 504_000, 1_000L));
        details.put(TX1, new TransactionDetailsDto(TX1, 2, 0, 562,
                List.of(new TransactionDetailsDto.Input(PREV1A, 0, EXT1, 3_000_000L, false),
                        new TransactionDetailsDto.Input(PREV1B, 1, EXT2, 2_500_000L, false),
                        new TransactionDetailsDto.Input(PREV1C, 0, EXT3, 2_405_000L, false),
                        new TransactionDetailsDto.Input(PREV1D, 3, EXT4, 1_000_000L, false)),
                List.of(new TransactionDetailsDto.Output(0, r0.address, 1_000_000, r0.scriptHex),
                        new TransactionDetailsDto.Output(1, r1.address, 5_000_000, r1.scriptHex),
                        new TransactionDetailsDto.Output(2, r2.address, 2_000_000, r2.scriptHex),
                        new TransactionDetailsDto.Output(3, EXT5, 900_000, EXT_SCRIPT)),
                8_905_000L, 8_900_000, 5_000L));
        details.put(TX2, new TransactionDetailsDto(TX2, 2, 0, 205,
                List.of(new TransactionDetailsDto.Input(PREV2, 0, EXT2, 120_722L, false)),
                List.of(new TransactionDetailsDto.Output(0, r3.address, 115_722, r3.scriptHex),
                        new TransactionDetailsDto.Output(1, EXT3, 4_000, EXT_SCRIPT)),
                120_722L, 119_722, 1_000L));
        details.put(TX3, new TransactionDetailsDto(TX3, 2, 0, 250,
                List.of(new TransactionDetailsDto.Input(TX0, 0, r4.address, 500_000L, false)),
                List.of(new TransactionDetailsDto.Output(0, EXT4, 400_000, EXT_SCRIPT),
                        new TransactionDetailsDto.Output(1, c0.address, 95_000, c0.scriptHex)),
                500_000L, 495_000, 5_000L));

        nextReceiveIndex = 5;
        tip = BASE_TIP;
        snapshot = buildSnapshot();
    }

    private void emitRandomTransaction() {
        tip++;
        int height = tip;
        long now = System.currentTimeMillis() / 1000L;
        long fee = 1_000L;
        String txid = randomTxid();

        List<Integer> spendable = IntStream.range(0, utxos.size())
                .filter(i -> utxos.get(i).confirmations() > 0)
                .boxed()
                .toList();
        boolean send = !spendable.isEmpty() && (utxos.size() >= MAX_UTXOS || random.nextInt(3) == 0);

        if (send) {
            UtxoDto u = utxos.remove((int) spendable.get(random.nextInt(spendable.size())));
            confirmed -= u.value();
            long extOut = Math.max(1L, u.value() - fee);
            txs.addFirst(new TransactionDto(txid, -u.value(), 0, u.value(), height, 1, now, TransactionType.SENT));
            details.put(txid, new TransactionDetailsDto(txid, 2, 0, 200,
                    List.of(new TransactionDetailsDto.Input(u.txid(), u.vout(), u.address(), u.value(), false)),
                    List.of(new TransactionDetailsDto.Output(0, ext(), extOut, EXT_SCRIPT)),
                    u.value(), extOut, fee));
        } else {
            long value = 20_000L + random.nextInt(3_000_000);
            AddressInfo addr = wallet.address(0, nextReceiveIndex++);
            utxos.add(0, new UtxoDto(txid, 0, value, height, 1, addr.address));
            confirmed += value;
            txs.addFirst(new TransactionDto(txid, value, value, 0, height, 1, now, TransactionType.RECEIVED));
            details.put(txid, new TransactionDetailsDto(txid, 2, 0, 205,
                    List.of(new TransactionDetailsDto.Input(randomTxid(), 0, ext(), value + fee, false)),
                    List.of(new TransactionDetailsDto.Output(0, addr.address, value, addr.scriptHex)),
                    value + fee, value, fee));
        }

        while (txs.size() > MAX_HISTORY) {
            details.remove(txs.removeLast().txid());
        }
    }

    private WalletSnapshot buildSnapshot() {
        AddressInfo next = wallet.address(0, nextReceiveIndex);
        return new WalletSnapshot(new BalanceDto(confirmed, unconfirmed),
                List.copyOf(utxos), List.copyOf(txs),
                new ReceiveAddressDto(next.index, next.address, next.path));
    }

    private String ext() {
        return EXT[random.nextInt(EXT.length)];
    }

    private String randomTxid() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
