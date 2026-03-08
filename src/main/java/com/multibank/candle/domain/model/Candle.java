package com.multibank.candle.domain.model;

/**
 * Immutable finalized OHLC candle. {@code time} is the bucket-start in UNIX seconds.
 * {@code volume} is the number of ticks (synthetic) that fell into this bucket.
 */
public record Candle(long time, double open, double high, double low, double close, long volume) {}
