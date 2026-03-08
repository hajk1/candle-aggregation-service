package com.multibank.candle.domain.model;

/**
 * Immutable finalized OHLC candle. {@code time} is the bucket-start in UNIX seconds.
 * {@code volume} is the number of ticks (synthetic) that fell into this bucket.
 */
public record Candle(long time, double open, double high, double low, double close, long volume) {

    /**
     * Merges {@code other} into this candle — used when replayed events arrive for an
     * already-finalized bucket. Open is preserved from the earlier candle (this),
     * close from the later (other), high/low are the global extremes, volumes are summed.
     */
    public Candle merge(Candle other) {
        return new Candle(
                this.time,
                this.open,
                Math.max(this.high, other.high),
                Math.min(this.low, other.low),
                other.close,
                this.volume + other.volume
        );
    }
}
