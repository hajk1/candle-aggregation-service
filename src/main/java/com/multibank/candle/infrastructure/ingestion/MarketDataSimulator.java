package com.multibank.candle.infrastructure.ingestion;

import com.multibank.candle.domain.model.BidAskEvent;
import com.multibank.candle.domain.port.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated market data source using a geometric random walk per symbol.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Depends only on {@link EventPublisher} — completely decoupled from aggregation logic.
 *       Replacing this with a Kafka consumer or WebSocket feed requires no changes elsewhere.</li>
 *   <li>Each symbol maintains its own last-price state. A random-walk step of ±0.05%
 *       produces realistic-looking tick sequences without external data.</li>
 *   <li>Spread is 0.01% of the current mid-price, a common approximation for liquid assets.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataSimulator {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSimulator.class);

    // Seed prices per well-known symbol; unknown symbols default to 1000.0
    private static final Map<String, Double> SEED_PRICES = Map.of(
            "BTC-USD", 30_000.0,
            "ETH-USD",  2_000.0,
            "SOL-USD",    100.0
    );

    private final EventPublisher publisher;
    private final List<String> symbols;
    private final Map<String, Double> lastPrice = new ConcurrentHashMap<>();
    private final Random rng = new Random();

    public MarketDataSimulator(EventPublisher publisher,
                               @Value("${simulator.symbols:BTC-USD,ETH-USD,SOL-USD}") String symbolsCsv) {
        this.publisher = publisher;
        this.symbols = List.of(symbolsCsv.split(","));
    }

    @Scheduled(fixedRateString = "${simulator.rate-ms:200}")
    public void tick() {
        // Pick a random symbol each tick to simulate interleaved multi-symbol feed
        String symbol = symbols.get(rng.nextInt(symbols.size()));
        double mid = nextMid(symbol);
        double spread = mid * 0.0001; // 1 bp spread

        BidAskEvent event = new BidAskEvent(
                symbol,
                mid - spread,
                mid + spread,
                Instant.now().getEpochSecond()
        );

        log.debug("Simulated tick: {}", event);
        publisher.publish(event);
    }

    private double nextMid(String symbol) {
        double seed = SEED_PRICES.getOrDefault(symbol, 1_000.0);
        // Geometric random walk: new = old * (1 + ε), ε ~ Uniform(-0.0005, +0.0005)
        return lastPrice.merge(symbol, seed,
                (prev, init) -> prev * (1.0 + (rng.nextDouble() - 0.5) * 0.001));
    }
}
