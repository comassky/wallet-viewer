package com.comassky.wallet.service;

import com.comassky.wallet.model.BalancePointDto;
import com.comassky.wallet.model.PricePointDto;
import com.comassky.wallet.model.TransactionDto;
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
                new TransactionDto("a", 50_000_000L, 50_000_000L, 0, 100, 3, twoDaysAgo, "received"));
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
    }

    @Test
    void daysBeforePriceHistoryUseTheEarliestQuote() {
        long now = Instant.now().getEpochSecond();
        long oneDayAgo = now - BalanceHistoryService.DAY;
        List<TransactionDto> txs = List.of(
                new TransactionDto("a", 100_000_000L, 100_000_000L, 0, 100, 3, oneDayAgo, "received"));
        // Price history only starts in the future relative to the transaction day.
        List<PricePointDto> prices = List.of(new PricePointDto(now + BalanceHistoryService.DAY, 80_000, 90_000));

        List<BalancePointDto> series = BalanceHistoryService.compute(txs, prices);

        assertFalse(series.isEmpty());
        assertEquals(80_000.0, series.get(0).valueEur(), 0.001);
    }
}
