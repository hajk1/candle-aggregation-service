package com.multibank.candle.application;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HistoryQueryService")
class HistoryQueryServiceTest {

    @Mock
    private CandleRepository repository;

    private HistoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new HistoryQueryService(repository);
    }

    @Test
    @DisplayName("delegates to repository with correct parameters")
    void delegatesToRepository() {
        when(repository.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_MINUTE, 1000L, 2000L))
                .thenReturn(List.of());

        service.query("BTC-USD", Interval.ONE_MINUTE, 1000L, 2000L);

        verify(repository).findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_MINUTE, 1000L, 2000L);
    }

    @Test
    @DisplayName("returns candles sorted ascending by time regardless of repository order")
    void sortsByTimeAscending() {
        Candle c1 = new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 5L);
        Candle c2 = new Candle(2000L, 105.0, 115.0, 95.0, 110.0, 8L);
        Candle c3 = new Candle(3000L, 110.0, 120.0, 100.0, 115.0, 6L);

        when(repository.findBySymbolAndIntervalBetween("BTC-USD", Interval.ONE_MINUTE, 1000L, 3000L))
                .thenReturn(List.of(c3, c1, c2)); // deliberately unsorted

        List<Candle> result = service.query("BTC-USD", Interval.ONE_MINUTE, 1000L, 3000L);

        assertThat(result).extracting(Candle::time).containsExactly(1000L, 2000L, 3000L);
    }

    @Test
    @DisplayName("returns empty list when repository has no matching candles")
    void emptyResult() {
        when(repository.findBySymbolAndIntervalBetween("ETH-USD", Interval.ONE_HOUR, 0L, 1L))
                .thenReturn(List.of());

        assertThat(service.query("ETH-USD", Interval.ONE_HOUR, 0L, 1L)).isEmpty();
    }
}
