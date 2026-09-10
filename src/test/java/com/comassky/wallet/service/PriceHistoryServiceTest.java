package com.comassky.wallet.service;

import com.comassky.wallet.model.PricePointDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PriceHistoryServiceTest {
    private final AtomicInteger requests = new AtomicInteger();

    private PriceHistoryService service(Supplier<MempoolClient.History> provider) {
        PriceHistoryService service = new PriceHistoryService();
        service.client = new StubClient(() -> {
            requests.incrementAndGet();
            return provider.get();
        });
        service.init();
        return service;
    }

    @Test
    void loadsSortsAscendingAndSharesCache() {
        PriceHistoryService service = service(PriceHistoryServiceTest::history);
        var result = Uni.combine().all().unis(service.history(), service.history()).asTuple()
                .await().atMost(Duration.ofSeconds(5));
        List<PricePointDto> points = result.getItem1();
        assertEquals(2, points.size());
        assertEquals(1_000, points.get(0).time());
        assertEquals(2_000, points.get(1).time());
        assertEquals(60_000, points.get(0).eur());
        assertEquals(70_000, points.get(0).usd());
        assertSame(result.getItem1(), result.getItem2());
        assertEquals(1, requests.get());
    }

    @Test
    void providerFailureReturns503() {
        PriceHistoryService service = service(() -> {
            throw new RuntimeException("provider down");
        });
        assertThrows(ServiceUnavailableException.class,
                () -> service.history().await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsEmptyOrInvalidHistory() {
        assertThrows(IllegalArgumentException.class,
                () -> PriceHistoryService.toPoints(new MempoolClient.History(List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> PriceHistoryService.toPoints(new MempoolClient.History(null)));
        assertThrows(IllegalArgumentException.class, () -> PriceHistoryService.toPoints(
                new MempoolClient.History(List.of(new MempoolClient.Price(0d, 0d, 1L)))));
    }

    @Test
    void failuresRetrySoonAndPreserveTheLastGoodHistory() {
        AtomicLong now = new AtomicLong();
        AtomicBoolean failing = new AtomicBoolean(true);
        PriceHistoryService service = service(() -> {
            if (failing.get()) throw new IllegalStateException("offline");
            return history();
        });
        service.nanoTime = now::get;
        assertThrows(ServiceUnavailableException.class, () -> service.history().await().atMost(Duration.ofSeconds(5)));
        failing.set(false);
        assertThrows(ServiceUnavailableException.class, () -> service.history().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
        now.addAndGet(PriceHistoryService.RETRY_DELAY.toNanos());
        List<PricePointDto> good = service.history().await().atMost(Duration.ofSeconds(5));
        assertFalse(service.stale());
        assertEquals(2, requests.get());
        failing.set(true);
        now.addAndGet(PriceHistoryService.TTL.toNanos());
        assertSame(good, service.history().await().atMost(Duration.ofSeconds(5)));
        assertTrue(service.stale());
        assertSame(good, service.history().await().atMost(Duration.ofSeconds(5)));
        assertEquals(3, requests.get());
        failing.set(false);
        now.addAndGet(PriceHistoryService.RETRY_DELAY.toNanos());
        assertEquals(good, service.history().await().atMost(Duration.ofSeconds(5)));
        assertFalse(service.stale());
        assertEquals(4, requests.get());
    }

    private static MempoolClient.History history() {
        // Unsorted on purpose: the service must sort ascending by time.
        return new MempoolClient.History(List.of(
                new MempoolClient.Price(61_000d, 71_000d, 2_000L),
                new MempoolClient.Price(60_000d, 70_000d, 1_000L)));
    }

    /** Cold stub: the supplier runs once per subscription, so memoize sharing is observable. */
    private record StubClient(Supplier<MempoolClient.History> provider) implements MempoolClient {
        @Override
        public Uni<Price> prices() {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Fees> feesRecommended() {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<History> historicalPrice() {
            return Uni.createFrom().item(provider);
        }
    }
}
