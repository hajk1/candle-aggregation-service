package com.multibank.candle.infrastructure.storage;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TimescaleDbCandleRepository} against a real
 * TimescaleDB instance managed by Testcontainers.
 *
 * <p>Flyway applies {@code V1__create_candles.sql} automatically on context startup,
 * so the hypertable and index are guaranteed to exist before any test runs.
 */
@SpringBootTest(properties = {
        "simulator.enabled=false",
        "candle.flush.interval-ms=999999"
})
@ActiveProfiles("timescale")
@Testcontainers
@DisplayName("TimescaleDbCandleRepository")
class TimescaleDbCandleRepositoryTest {

    @Container
    static PostgreSQLContainer<?> timescaledb = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg17")
                           .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("candles")
            .withUsername("candles")
            .withPassword("candles");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", timescaledb::getJdbcUrl);
        registry.add("spring.datasource.username", timescaledb::getUsername);
        registry.add("spring.datasource.password", timescaledb::getPassword);
    }

    @Autowired
    private TimescaleDbCandleRepository repository;

    private static final String SYMBOL = "BTC-USD";
    private static final long T0 = 1_620_000_060L;
    private static final long T1 = T0 + 60;
    private static final long T2 = T0 + 120;

    @BeforeEach
    void cleanUp() {
        // Truncate between tests to keep isolation
        repository.deleteAll();
    }

    @Test
    @DisplayName("saved candle is retrievable within the range")
    void saveAndQuery() {
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0),
                new Candle(T0, 29_500.0, 29_510.0, 29_490.0, 29_505.0, 10L));

        List<Candle> result = repository.findBySymbolAndIntervalBetween(SYMBOL, Interval.ONE_MINUTE, T0, T0);

        assertThat(result).hasSize(1);
        Candle c = result.get(0);
        assertThat(c.time()).isEqualTo(T0);
        assertThat(c.open()).isEqualTo(29_500.0);
        assertThat(c.high()).isEqualTo(29_510.0);
        assertThat(c.low()).isEqualTo(29_490.0);
        assertThat(c.close()).isEqualTo(29_505.0);
        assertThat(c.volume()).isEqualTo(10L);
    }

    @Test
    @DisplayName("results are sorted ascending by time")
    void resultsSortedAscending() {
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T2), new Candle(T2, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0), new Candle(T0, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T1), new Candle(T1, 1.0, 1.0, 1.0, 1.0, 1L));

        List<Candle> result = repository.findBySymbolAndIntervalBetween(SYMBOL, Interval.ONE_MINUTE, T0, T2);

        assertThat(result).extracting(Candle::time).containsExactly(T0, T1, T2);
    }

    @Test
    @DisplayName("from/to boundaries are inclusive")
    void inclusiveBoundaries() {
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0 - 60), new Candle(T0 - 60, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0), new Candle(T0, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T1), new Candle(T1, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T1 + 60), new Candle(T1 + 60, 1.0, 1.0, 1.0, 1.0, 1L));

        List<Candle> result = repository.findBySymbolAndIntervalBetween(SYMBOL, Interval.ONE_MINUTE, T0, T1);

        assertThat(result).extracting(Candle::time).containsExactly(T0, T1);
    }

    @Test
    @DisplayName("upsert merges high/low/volume on conflict — replay correctness")
    void upsertMerges() {
        CandleKey key = new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0);
        repository.save(key, new Candle(T0, 100.0, 110.0, 90.0, 105.0, 5L));
        repository.save(key, new Candle(T0, 106.0, 120.0, 80.0, 112.0, 3L));

        List<Candle> result = repository.findBySymbolAndIntervalBetween(SYMBOL, Interval.ONE_MINUTE, T0, T0);

        assertThat(result).hasSize(1);
        Candle merged = result.get(0);
        assertThat(merged.high()).isEqualTo(120.0);
        assertThat(merged.low()).isEqualTo(80.0);
        assertThat(merged.close()).isEqualTo(112.0);
        assertThat(merged.volume()).isEqualTo(8L);
    }

    @Test
    @DisplayName("symbol and interval filters are independent")
    void filtersAreIndependent() {
        repository.save(new CandleKey("BTC-USD", Interval.ONE_MINUTE, T0), new Candle(T0, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey("ETH-USD", Interval.ONE_MINUTE, T0), new Candle(T0, 2.0, 2.0, 2.0, 2.0, 1L));
        repository.save(new CandleKey("BTC-USD", Interval.ONE_HOUR, T0),   new Candle(T0, 3.0, 3.0, 3.0, 3.0, 1L));

        assertThat(repository.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_MINUTE, T0, T0)).hasSize(1);
        assertThat(repository.findBySymbolAndIntervalBetween("ETH-USD", Interval.ONE_MINUTE, T0, T0)).hasSize(1);
        assertThat(repository.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_HOUR,   T0, T0)).hasSize(1);
    }

    @Test
    @DisplayName("size() reflects stored candle count")
    void sizeReflectsCount() {
        assertThat(repository.size()).isEqualTo(0);
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0), new Candle(T0, 1.0, 1.0, 1.0, 1.0, 1L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T1), new Candle(T1, 1.0, 1.0, 1.0, 1.0, 1L));
        assertThat(repository.size()).isEqualTo(2);
    }
}
