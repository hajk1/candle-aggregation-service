package com.multibank.candle.infrastructure.storage;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * In-memory candle store with O(log n) range queries.
 *
 * <h3>Structure</h3>
 * <pre>
 * ConcurrentHashMap                         ← outer: keyed by (symbol, interval)
 *   └─ ConcurrentSkipListMap&lt;Long, Candle&gt;  ← inner: keyed by alignedTime, sorted
 * </pre>
 *
 * <p>Range queries use {@link ConcurrentSkipListMap#subMap} which is O(log n + result size)
 * rather than a full O(n) scan. Both maps are lock-free for reads and thread-safe for writes.
 */
@Repository
public class InMemoryCandleRepository implements CandleRepository {

    // Composite key for the outer map — avoids string concatenation
    private record SeriesKey(String symbol, Interval interval) {}

    private final ConcurrentMap<SeriesKey, ConcurrentSkipListMap<Long, Candle>> store =
            new ConcurrentHashMap<>();

    @Override
    public void save(CandleKey key, Candle candle) {
        store.computeIfAbsent(new SeriesKey(key.symbol(), key.interval()),
                        k -> new ConcurrentSkipListMap<>())
                .merge(key.alignedTime(), candle, Candle::merge);
    }

    @Override
    public List<Candle> findBySymbolAndIntervalBetween(String symbol, Interval interval,
                                                        long from, long to) {
        ConcurrentSkipListMap<Long, Candle> series =
                store.get(new SeriesKey(symbol, interval));

        if (series == null) return List.of();

        // subMap is O(log n + result size) — backed by the skip list, no full scan
        return new ArrayList<>(series.subMap(from, true, to, true).values());
    }

    /** Exposed for testing. */
    public int size() {
        return store.values().stream().mapToInt(ConcurrentSkipListMap::size).sum();
    }
}
