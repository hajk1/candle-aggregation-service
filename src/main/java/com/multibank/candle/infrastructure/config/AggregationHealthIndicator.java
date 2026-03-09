package com.multibank.candle.infrastructure.config;

import com.multibank.candle.application.CandleAggregationService;
import com.multibank.candle.domain.port.CandleRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator exposing aggregation-specific operational metrics.
 *
 * <p>Reported at {@code GET /actuator/health} under the {@code aggregation} key:
 * <pre>
 * "aggregation": {
 *   "status": "UP",
 *   "details": {
 *     "liveAccumulators": 15,
 *     "storedCandles":   300,
 *     "activeSymbols": ["BTC-USD", "ETH-USD", "SOL-USD"]
 *   }
 * }
 * </pre>
 */
@Component("aggregation")
public class AggregationHealthIndicator implements HealthIndicator {

    private final CandleAggregationService aggregationService;
    private final CandleRepository repository;

    public AggregationHealthIndicator(CandleAggregationService aggregationService,
                                      CandleRepository repository) {
        this.aggregationService = aggregationService;
        this.repository = repository;
    }

    @Override
    public Health health() {
        int live = aggregationService.liveCount();
        int stored = repository.size();

        return Health.up()
                .withDetail("liveAccumulators", live)
                .withDetail("storedCandles", stored)
                .withDetail("activeSymbols", aggregationService.activeSymbols())
                .build();
    }
}
