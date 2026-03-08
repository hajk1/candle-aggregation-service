package com.multibank.candle.application;

import com.multibank.candle.domain.model.BidAskEvent;
import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CandleAggregationService")
class CandleAggregationServiceTest {

    @Mock
    private CandleRepository repository;

    @Captor
    private ArgumentCaptor<CandleKey> keyCaptor;

    @Captor
    private ArgumentCaptor<Candle> candleCaptor;

    private CandleAggregationService service;

    // A timestamp that aligns to minute boundary 1620000060
    private static final long TS = 1620000073L;
    private static final String SYMBOL = "BTC-USD";

    @BeforeEach
    void setUp() {
        service = new CandleAggregationService(repository);
    }

    @Test
    @DisplayName("one event creates one accumulator per interval")
    void publishCreatesAccumulatorsForAllIntervals() {
        service.publish(new BidAskEvent(SYMBOL, 29_000.0, 29_002.0, TS));
        assertThat(service.liveCount()).isEqualTo(Interval.values().length);
    }

    @Test
    @DisplayName("events in the same bucket reuse the same accumulator")
    void sameKeyReusesAccumulator() {
        // Both events share the same timestamp → same bucket for every interval → no new accumulators created
        service.publish(new BidAskEvent(SYMBOL, 29_000.0, 29_002.0, TS));
        service.publish(new BidAskEvent(SYMBOL, 29_010.0, 29_012.0, TS));
        assertThat(service.liveCount()).isEqualTo(Interval.values().length);
    }

    @Test
    @DisplayName("different symbols produce independent accumulators")
    void differentSymbolsAreIndependent() {
        service.publish(new BidAskEvent("BTC-USD", 29_000.0, 29_002.0, TS));
        service.publish(new BidAskEvent("ETH-USD", 2_000.0, 2_001.0, TS));
        assertThat(service.liveCount()).isEqualTo(Interval.values().length * 2);
    }

    @Test
    @DisplayName("mid-price = (bid + ask) / 2 is used as OHLCV input")
    void midPriceCalculation() {
        // bid=29000, ask=29002 → mid=29001
        service.publish(new BidAskEvent(SYMBOL, 29_000.0, 29_002.0, TS));

        // force flush by publishing to a far-future timestamp that expires buckets
        long pastAligned = Interval.ONE_SECOND.align(TS);
        // Manually call flush with a far-future now by exercising the flush method
        // We test flush separately; here we just verify the accumulator snapshot.
        CandleKey key = new CandleKey(SYMBOL, Interval.ONE_SECOND, Interval.ONE_SECOND.align(TS));
        // The live map is package-private — we verify via flush
        // Flush won't fire yet because ts is not expired relative to real wall clock.
        // Instead verify that the snapshot would produce mid=29001.
        // We do this by calling flush after injecting an "expired" accumulator via another publish
        // at a very old timestamp (well before now).
        long ancientTs = 1_000L;
        service.publish(new BidAskEvent(SYMBOL, 29_000.0, 29_002.0, ancientTs));
        service.flush();

        verify(repository, atLeastOnce()).save(keyCaptor.capture(), candleCaptor.capture());
        Candle saved = candleCaptor.getAllValues().stream()
                .filter(c -> c.time() == Interval.ONE_SECOND.align(ancientTs))
                .findFirst()
                .orElseThrow();

        assertThat(saved.open()).isEqualTo(29_001.0);
        assertThat(saved.close()).isEqualTo(29_001.0);
        assertThat(saved.volume()).isEqualTo(1L);
    }

    @Test
    @DisplayName("flush saves expired accumulators and removes them from live map")
    void flushFinalizesExpiredBuckets() {
        long ancientTs = 1_000L; // well in the past — all intervals will be expired
        service.publish(new BidAskEvent(SYMBOL, 100.0, 102.0, ancientTs));
        int before = service.liveCount();

        service.flush();

        verify(repository, times(Interval.values().length)).save(any(), any());
        assertThat(service.liveCount()).isEqualTo(0); // all removed
        assertThat(before).isEqualTo(Interval.values().length);
    }

    @Test
    @DisplayName("flush does not touch live (non-expired) accumulators")
    void flushSkipsLiveBuckets() {
        // Publish with current wall-clock seconds — buckets will not be expired yet
        long nowSeconds = System.currentTimeMillis() / 1_000;
        service.publish(new BidAskEvent(SYMBOL, 100.0, 102.0, nowSeconds));

        service.flush();

        // The 1s bucket will expire immediately (nowSeconds + 1 <= now), but longer ones won't.
        // At minimum, most interval accumulators should still be alive.
        verify(repository, atMost(Interval.values().length - 1)).save(any(), any());
    }

    @Test
    @DisplayName("flush is idempotent — second call after buckets are gone saves nothing")
    void flushIdempotent() {
        service.publish(new BidAskEvent(SYMBOL, 100.0, 102.0, 1_000L));
        service.flush(); // clears all
        clearInvocations(repository);

        service.flush(); // nothing to flush
        verifyNoInteractions(repository);
    }
}
