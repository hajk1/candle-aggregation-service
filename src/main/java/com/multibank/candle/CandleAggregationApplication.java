package com.multibank.candle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CandleAggregationApplication {

    public static void main(String[] args) {
        SpringApplication.run(CandleAggregationApplication.class, args);
    }
}
