package com.comassky.wallet.service;

import com.comassky.wallet.model.FeeRatesDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class FeeServiceTest {
    private final AtomicInteger requests = new AtomicInteger();

    private FeeService service(Supplier<MempoolClient.Fees> provider) {
        FeeService service = new FeeService();
        service.client = new StubClient(() -> {
            requests.incrementAndGet();
            return provider.get();
        });
        service.init();
        return service;
    }

    @Test
    void loadsLazilyAndSharesCachedEstimates() {
        FeeService service = service(FeeServiceTest::recommended);
        Uni<FeeRatesDto> first = service.fees();
        assertEquals(0, requests.get());
        var result = Uni.combine().all().unis(first, service.fees()).asTuple()
                .await().atMost(Duration.ofSeconds(5));
        assertEquals(20, result.getItem1().fastest());
        assertEquals(12, result.getItem1().halfHour());
        assertEquals(8, result.getItem1().hour());
        assertEquals(3, result.getItem1().economy());
        assertEquals(1, result.getItem1().minimum());
        assertTrue(result.getItem1().timestamp() > 0);
        assertSame(result.getItem1(), result.getItem2());
        assertSame(result.getItem1(), service.fees().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void providerFailureReturns503AndIsBrieflyCached() {
        FeeService service = service(() -> {
            throw new RuntimeException("provider down");
        });
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class,
                () -> service.fees().await().atMost(Duration.ofSeconds(5)));
        assertEquals(503, error.getResponse().getStatus());
        assertThrows(ServiceUnavailableException.class,
                () -> service.fees().await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, requests.get());
    }

    @Test
    void invalidEstimateFailsAsServiceUnavailable() {
        FeeService service = service(() -> new MempoolClient.Fees(0L, 12L, 8L, 3L, 1L));
        assertThrows(ServiceUnavailableException.class,
                () -> service.fees().await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsMissingZeroAndNegativeEstimates() {
        assertThrows(IllegalArgumentException.class,
                () -> FeeService.toFees(new MempoolClient.Fees(null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> FeeService.toFees(new MempoolClient.Fees(0L, 12L, 8L, 3L, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> FeeService.toFees(new MempoolClient.Fees(20L, 12L, -1L, 3L, 1L)));
    }

    private static MempoolClient.Fees recommended() {
        return new MempoolClient.Fees(20L, 12L, 8L, 3L, 1L);
    }

    /** Cold stub: the supplier runs once per subscription, so memoize sharing is observable. */
    private record StubClient(Supplier<MempoolClient.Fees> provider) implements MempoolClient {
        @Override
        public Uni<Price> prices() {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Fees> feesRecommended() {
            return Uni.createFrom().item(provider);
        }

        @Override
        public Uni<History> historicalPrice() {
            return Uni.createFrom().nullItem();
        }
    }
}
