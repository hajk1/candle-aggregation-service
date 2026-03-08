package com.multibank.candle.application;

import com.multibank.candle.domain.model.BidAskEvent;
import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.infrastructure.storage.InMemoryCandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end aggregation flow: publish events → flush → query.
 * No Spring context — exercises the real collaborators directly.
 */
@DisplayName("Aggregation end-to-end")
class AggregationEndToEndTest {

    private static final long ANCIENT_TS = 1_000L; // far in the past — buckets expire immediately
    private static final String BTC = "BTC-USD";
    private static final String ETH = "ETH-USD";

    private InMemoryCandleRepository repository;
    private CandleAggregationService aggregationService;
    private HistoryQueryService queryService;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCandleRepository();
        aggregationService = new CandleAggregationService(repository, 0);
        queryService = new HistoryQueryService(repository);
    }

    @Test
    @DisplayName("published events produce correct OHLCV candle after flush")
    void publishFlushQuery() {
        // bid/ask → mid prices: 100, 120, 80, 110
        aggregationService.publish(new BidAskEvent(BTC, 99.0,  101.0, ANCIENT_TS));  // mid=100
        aggregationService.publish(new BidAskEvent(BTC, 119.0, 121.0, ANCIENT_TS));  // mid=120 (new high)
        aggregationService.publish(new BidAskEvent(BTC, 79.0,  81.0,  ANCIENT_TS));  // mid=80  (new low)
        aggregationService.publish(new BidAskEvent(BTC, 109.0, 111.0, ANCIENT_TS));  // mid=110 (close)

        aggregationService.flush();

        long aligned = Interval.ONE_SECOND.align(ANCIENT_TS);
        List<Candle> candles = queryService.query(BTC, Interval.ONE_SECOND, aligned, aligned);

        assertThat(candles).hasSize(1);
        Candle c = candles.get(0);
        assertThat(c.open()).isEqualTo(100.0);
        assertThat(c.high()).isEqualTo(120.0);
        assertThat(c.low()).isEqualTo(80.0);
        assertThat(c.close()).isEqualTo(110.0);
        assertThat(c.volume()).isEqualTo(4L);
    }

    @Test
    @DisplayName("multiple symbols are aggregated independently")
    void multipleSymbolsAreIndependent() {
        aggregationService.publish(new BidAskEvent(BTC, 29_999.0, 30_001.0, ANCIENT_TS)); // mid=30000
        aggregationService.publish(new BidAskEvent(ETH, 1_999.0,  2_001.0,  ANCIENT_TS)); // mid=2000

        aggregationService.flush();

        long aligned = Interval.ONE_SECOND.align(ANCIENT_TS);
        Candle btc = queryService.query(BTC, Interval.ONE_SECOND, aligned, aligned).get(0);
        Candle eth = queryService.query(ETH, Interval.ONE_SECOND, aligned, aligned).get(0);

        assertThat(btc.open()).isEqualTo(30_000.0);
        assertThat(eth.open()).isEqualTo(2_000.0);
    }

    @Test
    @DisplayName("same events produce candles for all supported intervals")
    void allIntervalsArePopulated() {
        aggregationService.publish(new BidAskEvent(BTC, 99.0, 101.0, ANCIENT_TS));
        aggregationService.flush();

        for (Interval interval : Interval.values()) {
            long aligned = interval.align(ANCIENT_TS);
            List<Candle> result = queryService.query(BTC, interval, aligned, aligned);
            assertThat(result)
                    .as("Expected one candle for interval %s", interval.getCode())
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("multiple buckets in same query range are all returned")
    void multipleBucketsInRange() {
        long t1 = 1_000L;
        long t2 = 2_000L; // different 1s buckets
        aggregationService.publish(new BidAskEvent(BTC, 99.0,  101.0, t1));
        aggregationService.publish(new BidAskEvent(BTC, 109.0, 111.0, t2));

        aggregationService.flush();

        List<Candle> result = queryService.query(BTC, Interval.ONE_SECOND, t1, t2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).time()).isLessThan(result.get(1).time()); // ascending
    }

    @Test
    @DisplayName("shutdown flush persists in-flight candles before expiry")
    void shutdownFlushPreservesLiveCandles() {
        long nowSeconds = System.currentTimeMillis() / 1_000;
        aggregationService.publish(new BidAskEvent(BTC, 99.0, 101.0, nowSeconds));

        aggregationService.flushOnShutdown();

        // All intervals should have a candle despite not being expired
        for (Interval interval : Interval.values()) {
            long aligned = interval.align(nowSeconds);
            List<Candle> result = queryService.query(BTC, interval, aligned, aligned);
            assertThat(result)
                    .as("Expected shutdown-flushed candle for interval %s", interval.getCode())
                    .hasSize(1);
        }
    }
}
