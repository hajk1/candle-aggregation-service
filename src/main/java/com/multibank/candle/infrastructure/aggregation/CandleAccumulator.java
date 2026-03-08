package com.multibank.candle.infrastructure.aggregation;

import com.multibank.candle.domain.model.Candle;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutable, thread-safe OHLCV accumulator for a single in-flight candle bucket.
 *
 * <h3>Concurrency design</h3>
 * A {@link ReentrantLock} protects the six correlated OHLCV fields as a unit.
 * Per-field atomics (e.g. {@code AtomicDouble}) cannot maintain invariants such as
 * {@code low ≤ close ≤ high} across concurrent reads, so a single narrow lock is
 * both simpler and correct. Lock scope is intentionally minimal — only field updates
 * and snapshots — so contention is bounded to a single bucket.
 *
 * <h3>Expiry</h3>
 * The accumulator is considered expired when the current wall-clock time has advanced
 * past the end of its bucket (alignedTime + intervalSeconds). A separate flush scheduler
 * checks this, finalizes the candle, and removes the accumulator from the live map.
 */
public class CandleAccumulator {

    private final long alignedTime;
    private final ReentrantLock lock = new ReentrantLock();

    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private boolean initialized = false;

    public CandleAccumulator(long alignedTime) {
        this.alignedTime = alignedTime;
    }

    /**
     * Applies a mid-price tick to the running OHLCV state.
     * The first call sets open; subsequent calls update high/low/close and increment volume.
     */
    public void apply(double midPrice) {
        lock.lock();
        try {
            if (!initialized) {
                open = high = low = close = midPrice;
                initialized = true;
            } else {
                if (midPrice > high) high = midPrice;
                if (midPrice < low)  low  = midPrice;
                close = midPrice;
            }
            volume++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an immutable {@link Candle} snapshot of the current state.
     * Safe to call concurrently with {@link #apply}.
     */
    public Candle snapshot() {
        lock.lock();
        try {
            return new Candle(alignedTime, open, high, low, close, volume);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this bucket's time window has fully elapsed.
     *
     * @param nowSeconds      current wall-clock time in UNIX seconds
     * @param intervalSeconds duration of the interval in seconds
     */
    public boolean isExpired(long nowSeconds, long intervalSeconds) {
        return nowSeconds >= alignedTime + intervalSeconds;
    }

    public long getAlignedTime() {
        return alignedTime;
    }
}
