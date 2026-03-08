package com.multibank.candle.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Candle")
class CandleTest {

    @Test
    @DisplayName("merge preserves open from receiver and close from argument")
    void mergePreservesOpenAndClose() {
        Candle first  = new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 5L);
        Candle second = new Candle(1000L, 106.0, 120.0, 80.0, 112.0, 3L);

        Candle merged = first.merge(second);

        assertThat(merged.open()).isEqualTo(100.0);  // from first
        assertThat(merged.close()).isEqualTo(112.0); // from second
    }

    @Test
    @DisplayName("merge takes global high and low")
    void mergeHighAndLow() {
        Candle first  = new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 5L);
        Candle second = new Candle(1000L, 106.0, 120.0, 80.0, 112.0, 3L);

        Candle merged = first.merge(second);

        assertThat(merged.high()).isEqualTo(120.0); // max(110, 120)
        assertThat(merged.low()).isEqualTo(80.0);   // min(90, 80)
    }

    @Test
    @DisplayName("merge sums volumes")
    void mergeVolume() {
        Candle first  = new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 5L);
        Candle second = new Candle(1000L, 106.0, 120.0, 80.0, 112.0, 3L);

        assertThat(first.merge(second).volume()).isEqualTo(8L);
    }

    @Test
    @DisplayName("merge preserves time from receiver")
    void mergePreservesTime() {
        Candle first  = new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 5L);
        Candle second = new Candle(1000L, 106.0, 120.0, 80.0, 112.0, 3L);

        assertThat(first.merge(second).time()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("merge is not symmetric — open/close direction matters")
    void mergeIsNotSymmetric() {
        Candle first  = new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 5L);
        Candle second = new Candle(1000L, 106.0, 120.0, 80.0, 112.0, 3L);

        Candle aB = first.merge(second);
        Candle bA = second.merge(first);

        assertThat(aB.open()).isNotEqualTo(bA.open());   // open differs by direction
        assertThat(aB.close()).isNotEqualTo(bA.close()); // close differs by direction
        assertThat(aB.high()).isEqualTo(bA.high());      // high/low/volume are symmetric
        assertThat(aB.low()).isEqualTo(bA.low());
        assertThat(aB.volume()).isEqualTo(bA.volume());
    }
}
