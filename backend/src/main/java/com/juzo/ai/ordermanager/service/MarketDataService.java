package com.juzo.ai.ordermanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.juzo.ai.ordermanager.entity.Stock;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final ConcurrentHashMap<String, Stock> stocks = new ConcurrentHashMap<>();
    private final ExecutorService priceUpdaterExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MarketDataService() {
        List.of(
            new Stock("AAPL",  "Apple Inc.",            new BigDecimal("189.30"), BigDecimal.ZERO),
            new Stock("MSFT",  "Microsoft Corp.",       new BigDecimal("415.50"), BigDecimal.ZERO),
            new Stock("NVDA",  "NVIDIA Corp.",          new BigDecimal("875.40"), BigDecimal.ZERO),
            new Stock("AMZN",  "Amazon.com Inc.",       new BigDecimal("182.75"), BigDecimal.ZERO),
            new Stock("GOOGL", "Alphabet Inc.",         new BigDecimal("175.20"), BigDecimal.ZERO),
            new Stock("META",  "Meta Platforms Inc.",   new BigDecimal("505.60"), BigDecimal.ZERO),
            new Stock("BRK.B", "Berkshire Hathaway B", new BigDecimal("395.10"), BigDecimal.ZERO),
            new Stock("LLY",   "Eli Lilly and Co.",    new BigDecimal("780.90"), BigDecimal.ZERO),
            new Stock("JPM",   "JPMorgan Chase & Co.", new BigDecimal("198.45"), BigDecimal.ZERO),
            new Stock("TSLA",  "Tesla Inc.",            new BigDecimal("177.80"), BigDecimal.ZERO)
        ).forEach(s -> stocks.put(s.ticker(), s));

        stocks.keySet().forEach(ticker ->
            priceUpdaterExecutor.submit(() -> simulatePriceUpdates(ticker))
        );
    }

    @PreDestroy
    public void shutdown() {
        priceUpdaterExecutor.shutdownNow();
    }

    public List<Stock> getStocks() {
        return List.copyOf(stocks.values());
    }

    private void simulatePriceUpdates(String ticker) {
        Random random = new Random();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(Duration.ofSeconds(1 + random.nextInt(10)));

                stocks.compute(ticker, (key, stock) -> {
                    if (stock == null) return null;
                    BigDecimal oldPrice = stock.price();
                    BigDecimal changeFraction = BigDecimal.valueOf(random.nextDouble() * 0.01);
                    BigDecimal delta = oldPrice.multiply(changeFraction).setScale(2, RoundingMode.HALF_UP);
                    boolean up = random.nextBoolean();
                    BigDecimal signedDelta = up ? delta : delta.negate();
                    BigDecimal newPrice = oldPrice.add(signedDelta)
                            .max(BigDecimal.ZERO)
                            .setScale(2, RoundingMode.HALF_UP);
                    log.info("{} {} -> {} ({})", ticker, oldPrice, newPrice, signedDelta);
                    return new Stock(stock.ticker(), stock.name(), newPrice, signedDelta);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}