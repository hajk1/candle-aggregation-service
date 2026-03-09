package com.multibank.candle.infrastructure.storage;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * TimescaleDB-backed candle store.
 *
 * <h3>Activated by</h3>
 * {@code --spring.profiles.active=timescale} (or {@code SPRING_PROFILES_ACTIVE=timescale}).
 *
 * <h3>Upsert strategy</h3>
 * {@code ON CONFLICT DO UPDATE} implements the same merge semantics as
 * {@link InMemoryCandleRepository}: high/low are global extremes, close is the
 * latest value, volumes are summed. This makes the save idempotent and replay-safe.
 *
 * <h3>Range query</h3>
 * TimescaleDB's chunk exclusion rewrites the {@code time BETWEEN ? AND ?} predicate
 * to skip irrelevant chunks entirely — O(chunks in range) rather than O(total rows).
 */
@Repository
@Profile("timescale")
public class TimescaleDbCandleRepository implements CandleRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO candles (time, symbol, interval, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, interval, time) DO UPDATE SET
                high   = GREATEST(candles.high,   EXCLUDED.high),
                low    = LEAST   (candles.low,    EXCLUDED.low),
                close  = EXCLUDED.close,
                volume = candles.volume + EXCLUDED.volume
            """;

    private static final String RANGE_QUERY_SQL = """
            SELECT time, open, high, low, close, volume
            FROM candles
            WHERE symbol = ? AND interval = ? AND time BETWEEN ? AND ?
            ORDER BY time ASC
            """;

    private static final RowMapper<Candle> CANDLE_ROW_MAPPER = (rs, rowNum) -> new Candle(
            rs.getLong("time"),
            rs.getDouble("open"),
            rs.getDouble("high"),
            rs.getDouble("low"),
            rs.getDouble("close"),
            rs.getLong("volume")
    );

    private final JdbcTemplate jdbc;

    public TimescaleDbCandleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(CandleKey key, Candle candle) {
        jdbc.update(UPSERT_SQL,
                candle.time(), key.symbol(), key.interval().getCode(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }

    @Override
    public List<Candle> findBySymbolAndIntervalBetween(String symbol, Interval interval,
                                                        long from, long to) {
        return jdbc.query(RANGE_QUERY_SQL, CANDLE_ROW_MAPPER,
                symbol, interval.getCode(), from, to);
    }

    @Override
    public int size() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM candles", Integer.class);
        return count != null ? count : 0;
    }

    /** Used in tests to reset state between test cases. */
    public void deleteAll() {
        jdbc.update("TRUNCATE TABLE candles");
    }
}
