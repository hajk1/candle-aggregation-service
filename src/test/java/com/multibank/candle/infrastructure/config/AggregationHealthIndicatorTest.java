package com.multibank.candle.infrastructure.config;

import com.multibank.candle.application.CandleAggregationService;
import com.multibank.candle.domain.model.BidAskEvent;
import com.multibank.candle.infrastructure.storage.InMemoryCandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AggregationHealthIndicator")
class AggregationHealthIndicatorTest {

    private InMemoryCandleRepository repository;
    private CandleAggregationService aggregationService;
    private AggregationHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryCandleRepository();
        aggregationService = new CandleAggregationService(repository, 0);
        indicator = new AggregationHealthIndicator(aggregationService, repository);
    }

    @Test
    @DisplayName("status is UP and details reflect empty state on startup")
    void upOnStartup() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("liveAccumulators", 0);
        assertThat(health.getDetails()).containsEntry("storedCandles", 0);
    }

    @Test
    @DisplayName("liveAccumulators increases after publishing events")
    void liveAccumulatorsReflectsPublish() {
        aggregationService.publish(new BidAskEvent("BTC-USD", 100.0, 102.0,
                System.currentTimeMillis() / 1000));

        Health health = indicator.health();
        assertThat((int) health.getDetails().get("liveAccumulators")).isPositive();
    }

    @Test
    @DisplayName("activeSymbols lists symbols with live accumulators")
    void activeSymbolsListed() {
        long ts = System.currentTimeMillis() / 1000;
        aggregationService.publish(new BidAskEvent("BTC-USD", 100.0, 102.0, ts));
        aggregationService.publish(new BidAskEvent("ETH-USD", 10.0, 11.0, ts));

        Health health = indicator.health();
        assertThat(health.getDetails().get("activeSymbols").toString())
                .contains("BTC-USD", "ETH-USD");
    }

    @Test
    @DisplayName("storedCandles increases after flush")
    void storedCandlesAfterFlush() {
        aggregationService.publish(new BidAskEvent("BTC-USD", 100.0, 102.0, 1_000L));
        aggregationService.flush();

        Health health = indicator.health();
        assertThat((int) health.getDetails().get("storedCandles")).isPositive();
    }
}
