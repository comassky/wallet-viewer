package com.comassky.wallet.service;

import com.comassky.wallet.model.BalancePointDto;
import com.comassky.wallet.model.PricePointDto;
import com.comassky.wallet.model.TransactionDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Daily balance valued at each day's BTC price, computed on the backend and cached briefly. */
@ApplicationScoped
public class BalanceHistoryService {
    static final long DAY = 86_400L;

    @Inject
    WalletLiveService live;

    @Inject
    PriceHistoryService priceHistory;

    private Uni<List<BalancePointDto>> cached;

    @PostConstruct
    void init() {
        // Deferred so each cache window re-reads the current snapshot and price history.
        cached = Uni.createFrom().deferred(() ->
                Uni.combine().all().unis(live.snapshot(), priceHistory.history()).asTuple()
                        .map(tuple -> compute(tuple.getItem1().transactions(), tuple.getItem2())))
                .memoize().forFixedDuration(Duration.ofSeconds(60));
    }

    public Uni<List<BalancePointDto>> history() {
        return cached;
    }

    static List<BalancePointDto> compute(List<TransactionDto> transactions, List<PricePointDto> prices) {
        if (transactions.isEmpty()) {
            return List.of();
        }
        final long nowDay = Instant.now().getEpochSecond() / DAY * DAY;
        final List<TransactionDto> ordered = transactions.stream()
                .sorted(Comparator.comparingLong((TransactionDto tx) -> tx.timestamp() == null ? Long.MAX_VALUE : tx.timestamp())
                        .thenComparingInt(TransactionDto::height))
                .toList();
        // End-of-day cumulative balance; pending transactions (no timestamp) fold into today.
        final TreeMap<Long, Long> balanceByDay = new TreeMap<>();
        long running = 0;
        for (TransactionDto tx : ordered) {
            running += tx.amount();
            final long time = tx.timestamp() == null ? nowDay : tx.timestamp();
            balanceByDay.put(time / DAY * DAY, running);
        }
        final long firstDay = balanceByDay.firstKey();
        final List<BalancePointDto> out = new ArrayList<>();
        long carried = 0;
        for (long day = firstDay; day <= nowDay; day += DAY) {
            final Long updated = balanceByDay.get(day);
            if (updated != null) carried = updated;
            final PricePointDto price = priceAt(prices, day + DAY - 1);
            final double btc = carried / 100_000_000.0;
            final double eur = price == null ? 0 : btc * price.eur();
            final double usd = price == null ? 0 : btc * price.usd();
            out.add(new BalancePointDto(day, carried, eur, usd));
        }
        return out;
    }

    /** Last price at or before the given time; the earliest point for days before the history starts. */
    private static PricePointDto priceAt(List<PricePointDto> prices, long time) {
        if (prices.isEmpty()) {
            return null;
        }
        int lo = 0, hi = prices.size() - 1, ans = -1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (prices.get(mid).time() <= time) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans >= 0 ? prices.get(ans) : prices.get(0);
    }
}
