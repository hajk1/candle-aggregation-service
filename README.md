# Candle Aggregation Service

[![CI](https://github.com/hajk1/candle-aggregation-service/actions/workflows/ci.yml/badge.svg)](https://github.com/hajk1/candle-aggregation-service/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-25-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen?logo=springboot)

A Spring Boot service that ingests a continuous stream of bid/ask market data, aggregates it into OHLC candlesticks per symbol and interval, and exposes a history API compatible with TradingView Lightweight Charts.

---

## Project Overview

```
BidAskEvent stream (simulated)
      │
      ▼
CandleAggregationService          ← implements EventPublisher port
  │  ConcurrentHashMap<CandleKey, CandleAccumulator>
  │  one accumulator per (symbol × interval × bucket)
  │
  ├── publish()  → fans out to all 5 intervals per tick
  └── flush()    → @Scheduled, finalizes expired buckets → CandleRepository
                                                                │
                                                        InMemoryCandleRepository
                                                        ConcurrentHashMap<CandleKey, Candle>
                                                                │
                                                        GET /history → HistoryController
```

### Supported intervals

| Code  | Duration |
|-------|----------|
| `1s`  | 1 second |
| `5s`  | 5 seconds |
| `1m`  | 1 minute |
| `15m` | 15 minutes |
| `1h`  | 1 hour |

---

## Running the Service

**Prerequisites:** Java 25, Maven 3.9+

```bash
cd candle-aggregation
mvn spring-boot:run
```

The service starts on **port 8080**.

| Endpoint | Description |
|----------|-------------|
| `GET /history?symbol=BTC-USD&interval=1m&from=1620000000&to=1620000600` | OHLC history |
| `GET /actuator/health` | Health check |
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

---

## Running Tests

```bash
# Unit + integration tests
mvn test

# Tests + coverage report + coverage gate
mvn verify

# Coverage report location
open target/site/jacoco/index.html
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
  aggregation/  CandleAccumulator  — mutable, lock-protected per-bucket state
  ingestion/    MarketDataSimulator — @Scheduled random-walk tick generator
  storage/      InMemoryCandleRepository — ConcurrentHashMap-backed store
  config/       OpenApiConfig

web/
  HistoryController           GET /history
  dto/HistoryResponse         columnar parallel-array response
  exception/GlobalExceptionHandler  400 / 500 mapping
```

---

## Assumptions & Trade-offs

### Mid-price as OHLC input
There is no trade price in the `BidAskEvent`. Mid-price `= (bid + ask) / 2` is the standard convention for quote-only feeds and is used as the price series for OHLC computation.

### Volume = tick count
The spec defines volume as "number of ticks" (synthetic). This is straightforward to replace with a notional amount if the event schema is extended.

### Concurrency strategy: per-bucket ReentrantLock
OHLCV has six correlated fields. Per-field atomics (e.g. `AtomicDouble`) cannot maintain invariants like `low ≤ close ≤ high` across a concurrent read. A single narrow lock scoped to one accumulator is both simpler and correct. The `ConcurrentHashMap` provides lock-free deduplication of accumulator creation via `computeIfAbsent`.

### Flush granularity
The flush scheduler runs every 1 second (configurable via `candle.flush.interval-ms`). Candles are finalized at most `flushInterval` after their bucket closes — not at the exact boundary. This is acceptable for a historical query API. For true real-time streaming, a bucket-transition-on-event approach would be needed.

### Late events are dropped
Events arriving for an already-finalized bucket are silently dropped (logged at WARN). Reopening closed buckets would break the API's monotonicity guarantee. A configurable `latenessThreshold` can be added in a future iteration.

### In-memory storage only
Data does not survive restarts. The `CandleRepository` port is the single abstraction point — a TimescaleDB implementation can be added behind a Spring profile (`@Profile("timescale")`) without touching any application-layer code.

### Time-alignment uses event timestamp
`alignedTime = (eventTimestamp / intervalSeconds) * intervalSeconds` — deterministic floor division on the event's own timestamp, no wall-clock dependency. This handles out-of-order events within the same bucket correctly.

### Simulated data source
The `MarketDataSimulator` uses a geometric random walk (±0.05% per tick) seeded at realistic prices (BTC ~$30,000, ETH ~$2,000). Replacing it with Kafka or a WebSocket feed requires implementing a new `@Component` that calls `EventPublisher.publish()` — no other changes.

---

## Bonus Features

- **OpenAPI / Swagger UI** — auto-generated interactive docs at `/swagger-ui.html`
- **Spring Actuator** — health/metrics at `/actuator/health`
- **JaCoCo coverage gate** — build fails below 65% line / 65% branch coverage; core classes (`CandleAccumulator`, `CandleAggregationService`) require ≥ 90% line coverage
- **ProblemDetail (RFC 9457)** — structured error responses with `title` and `detail` fields
