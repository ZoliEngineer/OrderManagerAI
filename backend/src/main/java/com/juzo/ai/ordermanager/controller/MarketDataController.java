package com.juzo.ai.ordermanager.controller;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.juzo.ai.ordermanager.entity.Stock;
import com.juzo.ai.ordermanager.service.MarketDataService;

@RestController
@CrossOrigin(origins = "*")
public class MarketDataController {

    private final MarketDataService marketDataService;

    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/api/marketdata")
    public List<Stock> getMarketData() {
        return marketDataService.getStocks();
    }
}
