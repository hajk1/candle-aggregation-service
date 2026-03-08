package com.multibank.candle.web.dto;

import com.multibank.candle.domain.model.Candle;

import java.util.List;

/**
 * Columnar (parallel-array) response format matching the TradingView Lightweight Charts API.
 *
 * <p>When data exists: {@code s = "ok"} and all arrays are non-empty and same-length.
 * When no data:        {@code s = "no_data"} and all arrays are empty.
 */
public record HistoryResponse(
        String s,
        long[] t,
        double[] o,
        double[] h,
        double[] l,
        double[] c,
        long[] v
) {

    public static HistoryResponse from(List<Candle> candles) {
        int n = candles.size();
        long[]   t = new long[n];
        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        long[]   v = new long[n];

        for (int i = 0; i < n; i++) {
            Candle candle = candles.get(i);
            t[i] = candle.time();
            o[i] = candle.open();
            h[i] = candle.high();
            l[i] = candle.low();
            c[i] = candle.close();
            v[i] = candle.volume();
        }

        return new HistoryResponse("ok", t, o, h, l, c, v);
    }

    public static HistoryResponse noData() {
        return new HistoryResponse("no_data",
                new long[0], new double[0], new double[0], new double[0], new double[0], new long[0]);
    }
}
