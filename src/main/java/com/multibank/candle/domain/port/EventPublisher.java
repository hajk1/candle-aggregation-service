package com.multibank.candle.domain.port;

import com.multibank.candle.domain.model.BidAskEvent;

/**
 * Inbound port: accepts a raw market-data tick for aggregation.
 *
 * <p>Any data source (simulator, Kafka consumer, WebSocket feed) depends only on this
 * interface, keeping ingestion fully decoupled from aggregation logic.
 */
public interface EventPublisher {
    void publish(BidAskEvent event);
}
