package com.comassky.wallet.service;

import com.comassky.wallet.model.BalancePointDto;
import com.comassky.wallet.model.PricePointDto;
import com.comassky.wallet.model.TransactionDto;
import com.comassky.wallet.model.TransactionType;
import com.comassky.wallet.model.WalletSnapshot;
import com.comassky.wallet.model.BalanceDto;
import com.comassky.wallet.model.ReceiveAddressDto;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BalanceHistoryServiceTest {

    @Test
    void emptyTransactionsYieldEmptySeries() {
        assertTrue(BalanceHistoryService.compute(List.of(), List.of()).isEmpty());
    }

    @Test
    void buildsGapFilledDailySeriesValuedAtEachDayPrice() {
        long now = Instant.now().getEpochSecond();
        long twoDaysAgo = now - 2 * BalanceHistoryService.DAY;
        // One received transaction of 0.5 BTC two days ago.
        List<TransactionDto> txs = List.of(
                new TransactionDto("a", 50_000_000L, 50_000_000L, 0, 100, 3, twoDaysAgo, TransactionType.RECEIVED));
        // A single price point predating the transaction: 60k EUR / 70k USD per BTC.
        List<PricePointDto> prices = List.of(new PricePointDto(twoDaysAgo - BalanceHistoryService.DAY, 60_000, 70_000));

        List<BalancePointDto> series = BalanceHistoryService.compute(txs, prices);

        assertFalse(series.isEmpty());
        assertEquals(3, series.size(), "two-days-ago, yesterday and today");
        BalancePointDto first = series.get(0);
        BalancePointDto last = series.get(series.size() - 1);
        assertEquals(50_000_000L, first.balanceSats());
        assertEquals(50_000_000L, last.balanceSats());
        // 0.5 BTC * 60_000 EUR = 30_000 EUR; * 70_000 USD = 35_000 USD.
        assertEquals(30_000.0, last.valueEur(), 0.001);
        assertEquals(35_000.0, last.valueUsd(), 0.001);
        assertEquals(60_000.0, last.priceEur(), 0.001);
        assertEquals(70_000.0, last.priceUsd(), 0.001);
    }

    @Test
    void daysBeforePriceHistoryHaveUnknownFiatValues() {
        long now = Instant.now().getEpochSecond();
        long oneDayAgo = now - BalanceHistoryService.DAY;
        List<TransactionDto> txs = List.of(
                new TransactionDto("a", 100_000_000L, 100_000_000L, 0, 100, 3, oneDayAgo, TransactionType.RECEIVED));
        // Price history only starts in the future relative to the transaction day.
        List<PricePointDto> prices = List.of(new PricePointDto(now + BalanceHistoryService.DAY, 80_000, 90_000));

        List<BalancePointDto> series = BalanceHistoryService.compute(txs, prices);

        assertFalse(series.isEmpty());
        assertNull(series.get(0).valueEur());
        assertNull(series.get(0).valueUsd());
        assertNull(series.get(0).priceTime());
        assertNull(series.get(0).priceEur());
        assertNull(series.get(0).priceUsd());
        assertEquals(100_000_000L, series.get(0).balanceSats());
    }

    @Test
    void zeroBalanceStillIncludesTheBitcoinPrice() {
        List<TransactionDto> transactions = List.of(
                new TransactionDto("a", 0L, 0L, 0L, 0, 0, null, TransactionType.SELF));
        List<PricePointDto> prices = List.of(new PricePointDto(0, 60_000, 70_000));

        BalancePointDto point = BalanceHistoryService.compute(transactions, prices).getFirst();

        assertEquals(0.0, point.valueEur(), 0.001);
        assertEquals(0.0, point.valueUsd(), 0.001);
        assertEquals(60_000.0, point.priceEur(), 0.001);
        assertEquals(70_000.0, point.priceUsd(), 0.001);
    }

    @Test
    void priceOutageDoesNotRemoveBitcoinHistory() {
        BalanceHistoryService service = new BalanceHistoryService();
        TransactionDto transaction = new TransactionDto("a", 123L, 123L, 0, 0, 0, null, TransactionType.RECEIVED);
        service.live = new WalletLiveService() {
            @Override public Uni<WalletSnapshot> snapshot() {
                return Uni.createFrom().item(new WalletSnapshot(new BalanceDto(0, 123), List.of(),
                        List.of(transaction), new ReceiveAddressDto(0, "test", "test")));
            }
        };
        service.priceHistory = new PriceHistoryService() {
            @Override public Uni<List<PricePointDto>> history() {
                return Uni.createFrom().failure(new IllegalStateException("offline"));
            }
        };
        service.init();
        try {
            BalancePointDto point = service.history().await().atMost(Duration.ofSeconds(5)).getFirst();
            assertEquals(123, point.balanceSats());
            assertNull(point.valueEur());
        } finally {
            service.live.stop();
        }
    }
}
