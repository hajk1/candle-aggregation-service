-- Enable TimescaleDB extension (pre-installed in the timescale/timescaledb Docker image)
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- Candle table — one row per (symbol, interval, time) bucket
CREATE TABLE IF NOT EXISTS candles (
    time        BIGINT              NOT NULL,   -- bucket-start in UNIX seconds
    symbol      VARCHAR(20)         NOT NULL,
    interval    VARCHAR(5)          NOT NULL,
    open        DOUBLE PRECISION    NOT NULL,
    high        DOUBLE PRECISION    NOT NULL,
    low         DOUBLE PRECISION    NOT NULL,
    close       DOUBLE PRECISION    NOT NULL,
    volume      BIGINT              NOT NULL,
    PRIMARY KEY (symbol, interval, time)
);

-- Convert to TimescaleDB hypertable partitioned by time
-- chunk_time_interval = 86400 seconds = 1 day
SELECT create_hypertable('candles', by_range('time', 86400));

-- Index for the common query pattern: symbol + interval + time range
CREATE INDEX IF NOT EXISTS idx_candles_lookup
    ON candles (symbol, interval, time DESC);
