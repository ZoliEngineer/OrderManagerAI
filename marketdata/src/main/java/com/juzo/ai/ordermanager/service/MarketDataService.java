package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;
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

    public MarketDataService(PriceUpdateSource priceUpdateSource, List<StockEventPublisher> publishers) {
        this.priceUpdateSource = priceUpdateSource;
        this.publishers = publishers;
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
        latestStocks.put(stock.ticker(), stock);
        publishers.forEach(p -> p.publish(stock));
    }

    public List<Stock> getStocks() {
        return new ArrayList<>(latestStocks.values());
    }
}
