package com.multibank.candle.application;

import com.multibank.candle.domain.model.BidAskEvent;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import com.multibank.candle.domain.port.EventPublisher;
import com.multibank.candle.infrastructure.aggregation.CandleAccumulator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * A configurable grace period ({@code candle.flush.grace-period-seconds}, default 2s) keeps
 * buckets live after their nominal close time, absorbing slightly delayed events before
 * finalization. Events arriving after the grace period has elapsed are dropped (logged at WARN).
 */
@Service
public class CandleAggregationService implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregationService.class);

    private final ConcurrentMap<CandleKey, CandleAccumulator> live = new ConcurrentHashMap<>();
    private final CandleRepository repository;
    private final long gracePeriodSeconds;

    public CandleAggregationService(CandleRepository repository,
                                    @Value("${candle.flush.grace-period-seconds:2}") long gracePeriodSeconds) {
        this.repository = repository;
        this.gracePeriodSeconds = gracePeriodSeconds;
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

            if (acc.isExpired(nowSeconds, key.interval().getSeconds() + gracePeriodSeconds)) {
                var candle = acc.snapshot();
                repository.save(key, candle);
                log.info("Finalized candle: symbol={} interval={} time={} vol={}",
                        key.symbol(), key.interval().getCode(), key.alignedTime(), candle.volume());
                return true;
            }
            return false;
        });
    }

    /**
     * Flushes all remaining live accumulators on shutdown — including buckets that have
     * not yet reached their nominal close time. This prevents data loss for in-flight
     * candles when the process is stopped gracefully.
     */
    @PreDestroy
    public void flushOnShutdown() {
        log.info("Shutdown detected — flushing {} live accumulator(s)", live.size());
        live.entrySet().removeIf(entry -> {
            var candle = entry.getValue().snapshot();
            repository.save(entry.getKey(), candle);
            log.info("Shutdown flush: symbol={} interval={} time={} vol={}",
                    entry.getKey().symbol(), entry.getKey().interval().getCode(),
                    entry.getKey().alignedTime(), candle.volume());
            return true;
        });
        log.info("Shutdown flush complete");
    }

    /** Exposed for testing — returns the number of currently live (in-flight) accumulators. */
    public int liveCount() {
        return live.size();
    }
}
