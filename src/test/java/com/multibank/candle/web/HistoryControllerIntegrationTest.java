package com.multibank.candle.web;

import com.multibank.candle.domain.model.Candle;
import com.multibank.candle.domain.model.CandleKey;
import com.multibank.candle.domain.model.Interval;
import com.multibank.candle.infrastructure.storage.InMemoryCandleRepository;
import com.multibank.candle.web.dto.HistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full HTTP round-trip integration tests.
 * The real Spring context starts on a random port; no mocking of any layer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "simulator.rate-ms=999999",    // disable live ticks during tests
                "candle.flush.interval-ms=999999" // disable background flush
        })
@DisplayName("GET /history integration")
class HistoryControllerIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private InMemoryCandleRepository repository;

    private static final String SYMBOL = "BTC-USD";
    private static final long T0 = 1_620_000_060L; // minute-aligned
    private static final long T1 = T0 + 60;
    private static final long T2 = T0 + 120;

    @BeforeEach
    void seed() {
        // Directly seed the repo to avoid scheduler timing flakiness
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T0),
                new Candle(T0, 29_500.0, 29_510.0, 29_490.0, 29_505.0, 10L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T1),
                new Candle(T1, 29_501.0, 29_505.0, 29_495.0, 29_502.0, 8L));
        repository.save(new CandleKey(SYMBOL, Interval.ONE_MINUTE, T2),
                new Candle(T2, 29_502.0, 29_520.0, 29_498.0, 29_515.0, 12L));
    }

    @Test
    @DisplayName("returns ok with columnar arrays for matching candles")
    void happyPath() {
        ResponseEntity<HistoryResponse> resp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, SYMBOL, "1m", T0, T2);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        HistoryResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.s()).isEqualTo("ok");
        assertThat(body.t()).containsExactly(T0, T1, T2);
        assertThat(body.o()).containsExactly(29_500.0, 29_501.0, 29_502.0);
        assertThat(body.v()).containsExactly(10L, 8L, 12L);
    }

    @Test
    @DisplayName("returns no_data when range has no candles")
    void noData() {
        ResponseEntity<HistoryResponse> resp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, SYMBOL, "1m", 0L, 1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().s()).isEqualTo("no_data");
        assertThat(resp.getBody().t()).isEmpty();
    }

    @Test
    @DisplayName("returns no_data for unknown symbol")
    void unknownSymbol() {
        ResponseEntity<HistoryResponse> resp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, "DOGE-USD", "1m", T0, T2);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().s()).isEqualTo("no_data");
    }

    @Test
    @DisplayName("returns 400 for unknown interval code")
    void badInterval() {
        ResponseEntity<String> resp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                String.class, SYMBOL, "2h", T0, T2);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("from boundary is inclusive, to boundary is inclusive")
    void boundaryInclusive() {
        // Only T1 in range
        ResponseEntity<HistoryResponse> resp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, SYMBOL, "1m", T1, T1);

        assertThat(resp.getBody().t()).containsExactly(T1);
    }

    @Test
    @DisplayName("actuator health reports aggregation details")
    void healthEndpointReportsAggregationDetails() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("aggregation");
        assertThat(resp.getBody()).contains("liveAccumulators");
        assertThat(resp.getBody()).contains("storedCandles");
    }

    @Test
    @DisplayName("candles are returned sorted ascending by time regardless of insertion order")
    void sortedAscending() {
        ResponseEntity<HistoryResponse> resp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, SYMBOL, "1m", T0, T2);

        long[] times = resp.getBody().t();
        for (int i = 1; i < times.length; i++) {
            assertThat(times[i]).isGreaterThan(times[i - 1]);
        }
    }

    @Test
    @DisplayName("different intervals are stored independently")
    void intervalIsolation() {
        // Seed an hourly candle at T0
        repository.save(new CandleKey(SYMBOL, Interval.ONE_HOUR, Interval.ONE_HOUR.align(T0)),
                new Candle(Interval.ONE_HOUR.align(T0), 29_000.0, 30_000.0, 28_000.0, 29_500.0, 100L));

        ResponseEntity<HistoryResponse> minuteResp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, SYMBOL, "1m", T0, T2);
        long hourFrom = Interval.ONE_HOUR.align(T0); // 1620000000 — the actual bucket start
        ResponseEntity<HistoryResponse> hourResp = rest.getForEntity(
                "/history?symbol={s}&interval={i}&from={f}&to={t}",
                HistoryResponse.class, SYMBOL, "1h", hourFrom, T2);

        assertThat(minuteResp.getBody().t()).hasSize(3);
        assertThat(hourResp.getBody().t()).hasSize(1);
    }
}
