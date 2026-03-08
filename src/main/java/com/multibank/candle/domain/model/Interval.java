package com.multibank.candle.domain.model;

import java.util.Arrays;

/**
 * Supported candlestick intervals.
 *
 * <p>Each interval knows its wire code (used in HTTP query params) and its duration in seconds.
 * Adding a new interval is a single enum constant — no parser changes required.
 *
 * <p>Time alignment uses integer floor division:
 * <pre>alignedTime = (eventTimestamp / seconds) * seconds</pre>
 * This is deterministic and requires no wall-clock reference.
 */
public enum Interval {

    ONE_SECOND("1s", 1),
    FIVE_SECONDS("5s", 5),
    ONE_MINUTE("1m", 60),
    FIFTEEN_MINUTES("15m", 900),
    ONE_HOUR("1h", 3600);

    private final String code;
    private final long seconds;

    Interval(String code, long seconds) {
        this.code = code;
        this.seconds = seconds;
    }

    public String getCode() {
        return code;
    }

    public long getSeconds() {
        return seconds;
    }

    /**
     * Returns the bucket-start timestamp for the given event timestamp.
     * E.g. timestamp=73, interval=1m(60s) → 60.
     */
    public long align(long timestampSeconds) {
        return (timestampSeconds / seconds) * seconds;
    }

    /**
     * Parses an interval from its wire code (e.g. "1m", "1h").
     *
     * @throws IllegalArgumentException if the code is not recognised
     */
    public static Interval fromCode(String code) {
        return Arrays.stream(values())
                .filter(i -> i.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown interval '%s'. Supported: %s".formatted(
                                code,
                                Arrays.stream(values()).map(Interval::getCode).toList())));
    }
}
