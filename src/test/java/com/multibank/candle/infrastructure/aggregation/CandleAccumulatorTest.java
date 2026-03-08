package com.multibank.candle.infrastructure.aggregation;

import com.multibank.candle.domain.model.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CandleAccumulator")
class CandleAccumulatorTest {

    private static final long ALIGNED_TIME = 1620000060L;

    // --- single-threaded correctness ---

    @Test
    @DisplayName("first tick sets open, high, low, close; volume = 1")
    void firstTick() {
        var acc = new CandleAccumulator(ALIGNED_TIME);
        acc.apply(100.0);

        Candle c = acc.snapshot();
        assertThat(c.open()).isEqualTo(100.0);
        assertThat(c.high()).isEqualTo(100.0);
        assertThat(c.low()).isEqualTo(100.0);
        assertThat(c.close()).isEqualTo(100.0);
        assertThat(c.volume()).isEqualTo(1L);
        assertThat(c.time()).isEqualTo(ALIGNED_TIME);
    }

    @Test
    @DisplayName("high and low track correctly across multiple ticks")
    void highLowTracking() {
        var acc = new CandleAccumulator(ALIGNED_TIME);
        acc.apply(100.0);
        acc.apply(110.0);  // new high
        acc.apply(90.0);   // new low
        acc.apply(105.0);  // close

        Candle c = acc.snapshot();
        assertThat(c.open()).isEqualTo(100.0);
        assertThat(c.high()).isEqualTo(110.0);
        assertThat(c.low()).isEqualTo(90.0);
        assertThat(c.close()).isEqualTo(105.0);
        assertThat(c.volume()).isEqualTo(4L);
    }

    @Test
    @DisplayName("close is always the last applied price")
    void closeIsLastPrice() {
        var acc = new CandleAccumulator(ALIGNED_TIME);
        acc.apply(200.0);
        acc.apply(150.0);
        acc.apply(175.0);

        assertThat(acc.snapshot().close()).isEqualTo(175.0);
    }

    @Test
    @DisplayName("snapshot is non-destructive — can be called multiple times")
    void snapshotNonDestructive() {
        var acc = new CandleAccumulator(ALIGNED_TIME);
        acc.apply(50.0);
        Candle first = acc.snapshot();
        acc.apply(60.0);
        Candle second = acc.snapshot();

        assertThat(first.volume()).isEqualTo(1L);
        assertThat(second.volume()).isEqualTo(2L);
    }

    @Test
    @DisplayName("isExpired returns false while within window, true after")
    void expiry() {
        var acc = new CandleAccumulator(1000L);
        assertThat(acc.isExpired(1059L, 60L)).isFalse(); // 1000 + 60 = 1060, now=1059 → still open
        assertThat(acc.isExpired(1060L, 60L)).isTrue();  // exactly at boundary → expired
        assertThat(acc.isExpired(1100L, 60L)).isTrue();
    }

    // --- multi-threaded safety ---

    @Test
    @DisplayName("concurrent apply: volume is exact and invariants hold")
    void concurrentApply() throws InterruptedException {
        int threads = 50;
        int ticksPerThread = 1_000;
        var acc = new CandleAccumulator(ALIGNED_TIME);
        var latch = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final double price = 100.0 + t;  // prices: 100..149
                pool.submit(() -> {
                    for (int i = 0; i < ticksPerThread; i++) {
                        acc.apply(price);
                    }
                    latch.countDown();
                });
            }
            latch.await();
        }

        Candle c = acc.snapshot();
        assertThat(c.volume()).isEqualTo((long) threads * ticksPerThread);
        assertThat(c.high()).isGreaterThanOrEqualTo(c.low());
        assertThat(c.close()).isBetween(c.low(), c.high());
        assertThat(c.open()).isBetween(100.0, 149.0);
    }
}
