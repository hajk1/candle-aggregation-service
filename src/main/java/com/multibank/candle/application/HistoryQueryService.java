package com.multibank.candle.application;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.domain.port.CandleRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Read-side use-case: retrieves and sorts historical candle data for a given query.
 */
@Service
public class HistoryQueryService {

    private final CandleRepository repository;

    public HistoryQueryService(CandleRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns finalized candles for the given symbol and interval in the time range
     * {@code [from, to]} (UNIX seconds, inclusive), sorted ascending by time.
     */
    public List<Candle> query(String symbol, Interval interval, long from, long to) {
        return repository.findBySymbolAndIntervalBetween(symbol, interval, from, to)
                .stream()
                .sorted(Comparator.comparingLong(Candle::time))
                .toList();
    }
}
