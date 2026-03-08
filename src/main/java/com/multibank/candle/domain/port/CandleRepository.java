package com.multibank.candle.domain.port;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;

import java.util.List;

/**
 * Outbound port: persistence abstraction for finalized candles.
 *
 * <p>Implementations: {@code InMemoryCandleRepository} (default).
 * A TimescaleDB implementation can be added without changing any application-layer code.
 */
public interface CandleRepository {

    /** Persists (or overwrites) a finalized candle. Must be thread-safe. */
    void save(CandleKey key, Candle candle);

    /**
     * Returns all candles for the given symbol and interval whose {@code time}
     * falls within {@code [from, to]} inclusive, in ascending time order.
     */
    List<Candle> findBySymbolAndIntervalBetween(String symbol, Interval interval, long from, long to);
}
