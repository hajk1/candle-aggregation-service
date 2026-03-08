package com.multibank.candle.infrastructure.storage;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryCandleRepository")
class InMemoryCandleRepositoryTest {

    private InMemoryCandleRepository repo;

    private static Candle candle(long time) {
        return new Candle(time, 100.0, 110.0, 90.0, 105.0, 10L);
    }

    @BeforeEach
    void setUp() {
        repo = new InMemoryCandleRepository();
    }

    @Test
    @DisplayName("saved candle is retrievable within the range")
    void saveAndQuery() {
        long t = 1620000060L;
        CandleKey key = new CandleKey("BTC-USD", Interval.ONE_MINUTE, t);
        repo.save(key, candle(t));

        List<Candle> result = repo.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_MINUTE, t, t);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).time()).isEqualTo(t);
    }

    @Test
    @DisplayName("results are sorted ascending by time")
    void resultsSortedAscending() {
        for (long t = 1620000240; t >= 1620000060; t -= 60) {
            repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, t), candle(t));
        }

        List<Candle> result = repo.findBySymbolAndIntervalBetween(
                "BTC-USD", Interval.ONE_MINUTE, 1620000060L, 1620000240L);

        assertThat(result).extracting(Candle::time)
                .containsExactly(1620000060L, 1620000120L, 1620000180L, 1620000240L);
    }

    @Test
    @DisplayName("query returns nothing when symbol does not match")
    void symbolMismatch() {
        long t = 1620000060L;
        repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, t), candle(t));

        List<Candle> result = repo.findBySymbolAndIntervalBetween("ETH-USD", Interval.ONE_MINUTE, t, t);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("query returns nothing when interval does not match")
    void intervalMismatch() {
        long t = 1620000060L;
        repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, t), candle(t));

        List<Candle> result = repo.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_HOUR, t, t);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("from/to boundaries are inclusive")
    void inclusiveBoundaries() {
        long from = 1620000060L;
        long to   = 1620000180L;
        repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, from - 60), candle(from - 60)); // before
        repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, from), candle(from));           // at from
        repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, to), candle(to));               // at to
        repo.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, to + 60), candle(to + 60));     // after

        List<Candle> result = repo.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_MINUTE, from, to);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Candle::time).containsExactly(from, to);
    }

    @Test
    @DisplayName("save overwrites existing candle for same key")
    void saveOverwrites() {
        CandleKey key = new CandleKey("BTC-USD", Interval.ONE_MINUTE, 1620000060L);
        repo.save(key, new Candle(1620000060L, 100.0, 120.0, 80.0, 115.0, 5L));
        repo.save(key, new Candle(1620000060L, 100.0, 130.0, 70.0, 125.0, 10L));

        List<Candle> result = repo.findBySymbolAndIntervalBetween(
                "BTC-USD", Interval.ONE_MINUTE, 1620000060L, 1620000060L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).high()).isEqualTo(130.0);
    }

    @Test
    @DisplayName("empty range returns empty list")
    void emptyRange() {
        List<Candle> result = repo.findBySymbolAndIntervalBetween(
                "BTC-USD", Interval.ONE_MINUTE, 0L, 0L);
        assertThat(result).isEmpty();
    }
}
