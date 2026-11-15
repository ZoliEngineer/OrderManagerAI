package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataService {

    private final ConcurrentHashMap<String, Stock> latestStocks = new ConcurrentHashMap<>();
    private final PriceUpdateSource priceUpdateSource;
    private final List<StockEventPublisher> publishers;
    private final Counter ticks;

    public MarketDataService(PriceUpdateSource priceUpdateSource, List<StockEventPublisher> publishers,
                             MeterRegistry meters) {
        this.priceUpdateSource = priceUpdateSource;
        this.publishers = publishers;
        this.ticks = Counter.builder("marketdata.ticks")
                .description("Price ticks received from the price source (Finnhub or simulator)")
                .register(meters);
        Gauge.builder("marketdata.stocks.tracked", latestStocks, ConcurrentHashMap::size)
                .description("Tickers with a latest price in memory")
                .register(meters);
    }

    @PostConstruct
    public void start() {
        priceUpdateSource.initialStocks().forEach(s -> latestStocks.put(s.ticker(), s));
        priceUpdateSource.start(this::onPriceUpdate);
    }

    @PreDestroy
    public void stop() {
        priceUpdateSource.stop();
    }

    private void onPriceUpdate(Stock stock) {
        ticks.increment();
        latestStocks.put(stock.ticker(), stock);
        publishers.forEach(p -> p.publish(stock));
    }

    public List<Stock> getStocks() {
        return new ArrayList<>(latestStocks.values());
    }
}
