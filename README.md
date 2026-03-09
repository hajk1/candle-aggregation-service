# Candle Aggregation Service

[![CI](https://github.com/hajk1/candle-aggregation-service/actions/workflows/ci.yml/badge.svg)](https://github.com/hajk1/candle-aggregation-service/actions/workflows/ci.yml)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)
![Java](https://img.shields.io/badge/Java-25-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen?logo=springboot)

A Spring Boot service that ingests a continuous stream of bid/ask market data, aggregates it into OHLC candlesticks per symbol and interval, and exposes a history API compatible with TradingView Lightweight Charts.

---

## Project Overview

```
BidAskEvent stream (simulated or real feed)
      │
      ▼
CandleAggregationService          ← implements EventPublisher port
  │  ConcurrentHashMap<CandleKey, CandleAccumulator>
  │  one accumulator per (symbol × interval × bucket)
  │
  ├── publish()  → fans out to all 5 intervals per tick
  └── flush()    → @Scheduled, finalizes expired buckets → CandleRepository
                                                                │
                                              ┌─────────────────┴──────────────────┐
                                   InMemoryCandleRepository         TimescaleDbCandleRepository
                                   ConcurrentSkipListMap            hypertable + upsert
                                   (default profile)                (timescale profile)
                                                                │
                                                        GET /history → HistoryController
```

### Supported intervals

| Code  | Duration   |
|-------|------------|
| `1s`  | 1 second   |
| `5s`  | 5 seconds  |
| `1m`  | 1 minute   |
| `15m` | 15 minutes |
| `1h`  | 1 hour     |

---

## Running the Service

**Prerequisites:** Java 25, Maven 3.9+

### Default (in-memory storage)

```bash
mvn spring-boot:run
```

### With TimescaleDB (recommended for production)

**Prerequisites:** Docker

```bash
# 1. Start TimescaleDB
docker compose up -d

# 2. Run with the timescale profile
mvn spring-boot:run -Dspring-boot.run.profiles=timescale
```

The service starts on **port 8080**.

| Endpoint | Description |
|----------|-------------|
| `GET /history?symbol=BTC-USD&interval=1m&from=1620000000&to=1620000600` | OHLC history |
| `GET /actuator/health` | Health check — includes live aggregation metrics |
| `GET /swagger-ui.html` | Interactive API docs |

### Example response

```json
{
  "s": "ok",
  "t": [1620000060, 1620000120],
  "o": [29500.5, 29501.0],
  "h": [29510.0, 29505.0],
  "l": [29490.0, 29500.0],
  "c": [29505.0, 29502.0],
  "v": [10, 8]
}
```

When no data exists for the range: `{"s": "no_data", "t": [], "o": [], ...}`

### Health check response

```json
{
  "status": "UP",
  "components": {
    "aggregation": {
      "status": "UP",
      "details": {
        "liveAccumulators": 15,
        "storedCandles": 300,
        "activeSymbols": ["BTC-USD", "ETH-USD", "SOL-USD"]
      }
    }
  }
}
```

---

## Running Tests

```bash
# Unit + integration tests (in-memory profile, no Docker needed)
mvn test

# Tests + coverage report + coverage gate
mvn verify

# Coverage report
open target/site/jacoco/index.html

# TimescaleDB integration tests (requires Docker — Testcontainers pulls the image automatically)
mvn test -Dspring.profiles.active=timescale
```

---

## Architecture

The service follows a **hexagonal (ports-and-adapters)** structure:

```
domain/
  model/      BidAskEvent, Candle, CandleKey, Interval
  port/       EventPublisher (in), CandleRepository (out)

application/
  CandleAggregationService   core use-case, implements EventPublisher
  HistoryQueryService        read-side use-case

infrastructure/
  aggregation/  CandleAccumulator           — mutable, lock-protected per-bucket state
  ingestion/    MarketDataSimulator          — @Scheduled random-walk tick generator
  storage/      InMemoryCandleRepository     — ConcurrentSkipListMap, O(log n) queries
                TimescaleDbCandleRepository  — hypertable + upsert (timescale profile)
  config/       OpenApiConfig, AggregationHealthIndicator

web/
  HistoryController            GET /history
  dto/HistoryResponse          columnar parallel-array response
  exception/GlobalExceptionHandler  RFC 9457 ProblemDetail errors
```

### Storage profiles

| Profile | Implementation | Query complexity | Persistence |
|---------|---------------|-----------------|-------------|
| default | `InMemoryCandleRepository` | O(log n + results) via `ConcurrentSkipListMap.subMap` | Lost on restart |
| `timescale` | `TimescaleDbCandleRepository` | O(chunks in range) via TimescaleDB chunk exclusion | Durable |

The `CandleRepository` port is the single switch point — no application-layer code changes when switching profiles.

---

## Assumptions & Trade-offs

### Mid-price as OHLC input
There is no trade price in the `BidAskEvent`. Mid-price `= (bid + ask) / 2` is the standard convention for quote-only feeds.

### Volume = tick count
The spec defines volume as "number of ticks" (synthetic). Straightforward to replace with a notional amount if the event schema is extended.

### Concurrency: per-bucket ReentrantLock
OHLCV has six correlated fields. Per-field atomics cannot maintain invariants like `low ≤ close ≤ high` across concurrent reads. A single narrow lock scoped to one accumulator is correct and avoids cross-symbol contention entirely.

### Flush granularity + grace period
The flush scheduler runs every 1 second (`candle.flush.interval-ms`). A configurable grace period (`candle.flush.grace-period-seconds`, default 2s) holds buckets open after their nominal close to absorb delayed events before finalization.

### Late events are merged, not dropped
Events arriving after a bucket is flushed recreate the accumulator. On the next flush, `Candle.merge()` / `ON CONFLICT DO UPDATE` merges the new data into the existing candle — no split candles, no data loss.

### Graceful shutdown
`@PreDestroy` flushes all live accumulators before the JVM exits. `server.shutdown=graceful` drains in-flight HTTP requests first.

### Time-alignment uses event timestamp
`alignedTime = (eventTimestamp / intervalSeconds) * intervalSeconds` — deterministic floor division, no wall-clock dependency. Out-of-order events within the same bucket are handled correctly.

### Simulated data source is opt-out
`MarketDataSimulator` is guarded by `@ConditionalOnProperty(simulator.enabled, default=true)`. Set `simulator.enabled=false` when connecting a real feed (Kafka, WebSocket) — no other changes required.

---

## Bonus Features

- **TimescaleDB storage** — hypertable with chunk exclusion for O(log n) range queries; upsert handles replay safely
- **Graceful shutdown** — `@PreDestroy` flush + `server.shutdown=graceful`
- **Grace period for delayed events** — configurable window before bucket finalization
- **Late-event WARN logging** — logs symbol, interval, and delay in seconds
- **Custom health indicator** — `/actuator/health` reports live accumulators, stored candles, and active symbols
- **OpenAPI / Swagger UI** — interactive docs at `/swagger-ui.html`
- **JaCoCo coverage gate** — build fails below 65% line/branch; core classes require ≥ 90%
- **RFC 9457 ProblemDetail** — structured error responses
- **GitHub Actions CI** — builds, tests, enforces coverage, and auto-commits coverage badges
