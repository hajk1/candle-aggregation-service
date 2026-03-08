package com.multibank.candle.web;

import com.multibank.candle.application.HistoryQueryService;
import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.web.dto.HistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes historical candlestick data in the TradingView columnar format.
 *
 * <p>Interval parsing and validation happens here, at the HTTP boundary, so the
 * domain remains free of web-layer concerns.
 */
@RestController
@Tag(name = "History", description = "OHLC candlestick history API")
public class HistoryController {

    private final HistoryQueryService queryService;

    public HistoryController(HistoryQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Returns OHLC candle history in columnar format.
     *
     * @param symbol   e.g. {@code BTC-USD}
     * @param interval e.g. {@code 1m}, {@code 1h} — must match a supported {@link Interval} code
     * @param from     range start (UNIX seconds, inclusive)
     * @param to       range end   (UNIX seconds, inclusive)
     */
    @GetMapping("/history")
    @Operation(summary = "Get OHLC candlestick history")
    public ResponseEntity<HistoryResponse> getHistory(
            @Parameter(description = "Symbol, e.g. BTC-USD") @RequestParam String symbol,
            @Parameter(description = "Interval code: 1s, 5s, 1m, 15m, 1h") @RequestParam String interval,
            @Parameter(description = "Range start in UNIX seconds") @RequestParam long from,
            @Parameter(description = "Range end in UNIX seconds")   @RequestParam long to) {

        Interval parsedInterval = Interval.fromCode(interval); // throws IllegalArgumentException for unknowns
        List<Candle> candles = queryService.query(symbol, parsedInterval, from, to);

        HistoryResponse response = candles.isEmpty()
                ? HistoryResponse.noData()
                : HistoryResponse.from(candles);

        return ResponseEntity.ok(response);
    }
}
