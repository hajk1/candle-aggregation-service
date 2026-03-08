package com.multibank.candle.domain.model;

/**
 * Composite key that uniquely identifies a candle bucket.
 *
 * <p>Records auto-generate correct {@code equals} / {@code hashCode} based on all three
 * components, making this safe to use as a {@link java.util.concurrent.ConcurrentHashMap} key.
 *
 * @param symbol      e.g. "BTC-USD"
 * @param interval    the aggregation interval
 * @param alignedTime bucket-start in UNIX seconds (already floor-aligned to the interval)
 */
public record CandleKey(String symbol, Interval interval, long alignedTime) {}
