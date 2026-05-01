package com.juzo.ai.ordermanager.controller;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.juzo.ai.ordermanager.entity.MarketStatus;
import com.juzo.ai.ordermanager.entity.Stock;
import com.juzo.ai.ordermanager.service.FinnhubMarketStatusService;
import com.juzo.ai.ordermanager.service.MarketDataService;

import reactor.core.publisher.Mono;

@RestController
public class MarketDataController {

    private static final Logger log = LoggerFactory.getLogger(MarketDataController.class);

    private static final MarketStatus FALLBACK_STATUS =
        new MarketStatus("US", null, false, "unavailable", "America/New_York", 0);

    private final MarketDataService marketDataService;
    private final Optional<FinnhubMarketStatusService> marketStatusService;

    public MarketDataController(MarketDataService marketDataService,
                                Optional<FinnhubMarketStatusService> marketStatusService) {
        this.marketDataService = marketDataService;
        this.marketStatusService = marketStatusService;
    }

    @GetMapping("/api/marketdata")
    public ResponseEntity<List<Stock>> getMarketData() {
        List<Stock> stocks = marketDataService.getStocks();
        if (stocks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(stocks);
    }

    @GetMapping("/api/market-status")
    public Mono<ResponseEntity<MarketStatus>> getMarketStatus() {
        return marketStatusService
            .map(svc -> svc.getMarketStatus()
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.ok(FALLBACK_STATUS)))
            .orElseGet(() -> Mono.just(ResponseEntity.ok(FALLBACK_STATUS)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        log.error("Unexpected error in MarketDataController", ex);
        return ResponseEntity.internalServerError().body("Failed to retrieve market data");
    }
}
