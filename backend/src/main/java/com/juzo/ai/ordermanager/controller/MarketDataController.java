package com.juzo.ai.ordermanager.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.juzo.ai.ordermanager.entity.Stock;
import com.juzo.ai.ordermanager.service.MarketDataService;

@RestController
@CrossOrigin(origins = "${cors.allowed-origins}")
public class MarketDataController {

    private static final Logger log = LoggerFactory.getLogger(MarketDataController.class);

    private final MarketDataService marketDataService;

    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/api/marketdata")
    public ResponseEntity<List<Stock>> getMarketData() {
        List<Stock> stocks = marketDataService.getStocks();
        if (stocks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(stocks);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        log.error("Unexpected error in MarketDataController", ex);
        return ResponseEntity.internalServerError().body("Failed to retrieve market data");
    }
}
