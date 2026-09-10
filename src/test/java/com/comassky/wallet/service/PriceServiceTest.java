package com.comassky.wallet.service;

import com.comassky.wallet.model.PriceRatesDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PriceServiceTest {
    @Test
    void freshnessBoundariesUseTheSuppliedClock() {
        long now = 1_700_000_000L;
        Clock clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
        assertDoesNotThrow(() -> PriceService.toRates(new MempoolClient.Price(60_000d, 70_000d, now - 900), clock));
        assertDoesNotThrow(() -> PriceService.toRates(new MempoolClient.Price(60_000d, 70_000d, now + 300), clock));
        assertThrows(IllegalArgumentException.class, () -> PriceService.toRates(new MempoolClient.Price(60_000d, 70_000d, now - 901), clock));
        assertThrows(IllegalArgumentException.class, () -> PriceService.toRates(new MempoolClient.Price(60_000d, 70_000d, now + 301), clock));
    }

    private final AtomicInteger requests = new AtomicInteger();

    private PriceService service(Supplier<MempoolClient.Price> provider) {
        PriceService service = new PriceService();
        service.client = new StubClient(() -> {
            requests.incrementAndGet();
            return provider.get();
        });
        service.init();
        return service;
    }

    @Test
    void loadsLazilyAndSharesCachedQuotes() {
        long now = Instant.now().getEpochSecond();
        PriceService service = service(() -> new MempoolClient.Price(60_000d, 70_000d, now));
        Uni<PriceRatesDto> first = service.rates();
        assertEquals(0, requests.get());
        var result = Uni.combine().all().unis(first, service.rates()).asTuple()
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(60_000, result.getItem1().eur());
        assertEquals(70_000, result.getItem1().usd());
        assertSame(result.getItem1(), result.getItem2());
        assertSame(result.getItem1(), service.rates().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void providerFailureReturns503AndIsBrieflyCached() {
        PriceService service = service(() -> {
            throw new RuntimeException("provider down");
        });
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> service.rates().await().atMost(Duration.ofSeconds(5)));
        assertEquals(503, error.getResponse().getStatus());
        assertThrows(ServiceUnavailableException.class,
                () -> service.rates().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void invalidQuoteFailsAsServiceUnavailable() {
        long now = Instant.now().getEpochSecond();
        PriceService service = service(() -> new MempoolClient.Price(0d, 70_000d, now));
        assertThrows(ServiceUnavailableException.class,
                () -> service.rates().await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsMissingZeroNegativeAndOutdatedQuotes() {
        long now = Instant.now().getEpochSecond();
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.toRates(new MempoolClient.Price(null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.toRates(new MempoolClient.Price(0d, 70_000d, now)));
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.toRates(new MempoolClient.Price(60_000d, -1d, now)));
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.toRates(new MempoolClient.Price(60_000d, 70_000d, now - 3600)));
        assertThrows(IllegalArgumentException.class,
                () -> PriceService.toRates(new MempoolClient.Price(60_000d, 70_000d, now + 3600)));
    }

    /** Cold stub: the supplier runs once per subscription, so memoize sharing is observable. */
    private record StubClient(Supplier<MempoolClient.Price> provider) implements MempoolClient {
        @Override
        public Uni<Price> prices() {
            return Uni.createFrom().item(provider);
        }

        @Override
        public Uni<Fees> feesRecommended() {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<History> historicalPrice() {
            return Uni.createFrom().nullItem();
        }
    }
}
