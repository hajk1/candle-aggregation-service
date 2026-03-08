package com.multibank.candle.application;

import com.multibank.candle.domain.model.BidAskEvent;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import com.multibank.candle.domain.port.EventPublisher;
import com.multibank.candle.infrastructure.aggregation.CandleAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Core aggregation use-case.
 *
 * <h3>Concurrency design</h3>
 * <ul>
 *   <li>A {@link ConcurrentHashMap} holds live (in-flight) accumulators keyed by
 *       {@link CandleKey}. {@code computeIfAbsent} guarantees exactly-once creation
 *       per key even under concurrent writers — no global lock needed.</li>
 *   <li>Each event fans out to <em>all</em> configured intervals, so one tick produces
 *       N accumulator updates in parallel paths (one per interval).</li>
 *   <li>Finalization (bucket rollover) is handled by a separate scheduled flush thread,
 *       decoupling latency-sensitive ingestion from slower persistence I/O.</li>
 * </ul>
 *
 * <h3>Late-event policy</h3>
 * Events that arrive for an already-finalized bucket are silently dropped (logged at WARN).
 * Re-opening closed buckets would complicate the API guarantee; a configurable
 * {@code latenessThreshold} can be added in a future iteration.
 */
@Service
public class CandleAggregationService implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregationService.class);

    private final ConcurrentMap<CandleKey, CandleAccumulator> live = new ConcurrentHashMap<>();
    private final CandleRepository repository;

    public CandleAggregationService(CandleRepository repository) {
        this.repository = repository;
    }

    /**
     * Accepts a raw tick and routes it to the appropriate accumulator for every interval.
     * Mid-price = (bid + ask) / 2 is the standard convention when no trade price exists.
     */
    @Override
    public void publish(BidAskEvent event) {
        double mid = (event.bid() + event.ask()) / 2.0;
        log.debug("Received tick: symbol={} mid={} ts={}", event.symbol(), mid, event.timestamp());

        for (Interval interval : Interval.values()) {
            long aligned = interval.align(event.timestamp());
            CandleKey key = new CandleKey(event.symbol(), interval, aligned);

            CandleAccumulator acc = live.computeIfAbsent(key, k -> new CandleAccumulator(aligned));
            acc.apply(mid);
        }
    }

    /**
     * Scheduled flush: finalizes any expired buckets and moves them to the repository.
     *
     * <p>{@link ConcurrentHashMap#entrySet()} {@code removeIf} is documented as
     * thread-safe and atomic per-entry in Java 8+.
     */
    @Scheduled(fixedRateString = "${candle.flush.interval-ms:1000}")
    public void flush() {
        long nowSeconds = Instant.now().getEpochSecond();

        live.entrySet().removeIf(entry -> {
            CandleKey key = entry.getKey();
            CandleAccumulator acc = entry.getValue();

            if (acc.isExpired(nowSeconds, key.interval().getSeconds())) {
                var candle = acc.snapshot();
                repository.save(key, candle);
                log.info("Finalized candle: symbol={} interval={} time={} vol={}",
                        key.symbol(), key.interval().getCode(), key.alignedTime(), candle.volume());
                return true;
            }
            return false;
        });
    }

    /** Exposed for testing — returns the number of currently live (in-flight) accumulators. */
    public int liveCount() {
        return live.size();
    }
}
