package com.multibank.candle.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Interval")
class IntervalTest {

    @ParameterizedTest(name = "{0} align({1}) = {2}")
    @CsvSource({
            "ONE_SECOND,    1620000073, 1620000073",
            "FIVE_SECONDS,  1620000073, 1620000070",
            "ONE_MINUTE,    1620000073, 1620000060",
            "FIFTEEN_MINUTES, 1620000073, 1620000000",
            "ONE_HOUR,      1620000073, 1620000000",
    })
    @DisplayName("aligns timestamp to bucket start")
    void align(String intervalName, long timestamp, long expected) {
        Interval interval = Interval.valueOf(intervalName);
        assertThat(interval.align(timestamp)).isEqualTo(expected);
    }

    @Test
    @DisplayName("align is idempotent on already-aligned timestamps")
    void alignIdempotent() {
        long aligned = Interval.ONE_MINUTE.align(1620000060L);
        assertThat(Interval.ONE_MINUTE.align(aligned)).isEqualTo(aligned);
    }

    @ParameterizedTest(name = "fromCode({0}) = {1}")
    @CsvSource({"1s,ONE_SECOND", "5s,FIVE_SECONDS", "1m,ONE_MINUTE", "15m,FIFTEEN_MINUTES", "1h,ONE_HOUR"})
    @DisplayName("fromCode resolves all supported codes")
    void fromCodeHappy(String code, String expectedName) {
        assertThat(Interval.fromCode(code).name()).isEqualTo(expectedName);
    }

    @Test
    @DisplayName("fromCode throws for unknown codes")
    void fromCodeUnknown() {
        assertThatThrownBy(() -> Interval.fromCode("2h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2h");
    }

    @Test
    @DisplayName("getSeconds returns correct duration")
    void getSeconds() {
        assertThat(Interval.ONE_MINUTE.getSeconds()).isEqualTo(60);
        assertThat(Interval.ONE_HOUR.getSeconds()).isEqualTo(3600);
    }
}
