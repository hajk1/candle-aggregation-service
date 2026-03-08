package com.multibank.candle.infrastructure.storage;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory candle store backed by a {@link ConcurrentHashMap}.
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>{@link CandleKey} already encodes {@code (symbol, interval, alignedTime)}, so the map
 *       key is fully qualified — no secondary index is needed.</li>
 *   <li>{@code save} uses {@code put}, which is O(1) and thread-safe.</li>
 *   <li>Range queries stream and filter — acceptable for an in-memory store with a bounded
 *       number of historical candles. A real time-series DB (TimescaleDB) would use an index
 *       scan instead.</li>
 *   <li>Results are returned already sorted ascending by time; sorting here is defensive and
 *       cheap.</li>
 * </ul>
 */
@Repository
public class InMemoryCandleRepository implements CandleRepository {

    private final ConcurrentMap<CandleKey, Candle> store = new ConcurrentHashMap<>();

    @Override
    public void save(CandleKey key, Candle candle) {
        store.put(key, candle);
    }

    @Override
    public List<Candle> findBySymbolAndIntervalBetween(String symbol, Interval interval,
                                                        long from, long to) {
        return store.entrySet().stream()
                .filter(e -> e.getKey().symbol().equals(symbol))
                .filter(e -> e.getKey().interval() == interval)
                .filter(e -> e.getKey().alignedTime() >= from && e.getKey().alignedTime() <= to)
                .map(e -> e.getValue())
                .sorted(Comparator.comparingLong(Candle::time))
                .toList();
    }

    /** Exposed for testing. */
    public int size() {
        return store.size();
    }
}
