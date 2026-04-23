package com.juzo.ai.ordermanager.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.juzo.ai.ordermanager.entity.Stock;

@RestController
@CrossOrigin(origins = "*")
public class MarketDataController {

    @GetMapping("/api/marketdata")
    public List<Stock> getMarketData() {
        return List.of(
            new Stock("AAPL",  "Apple Inc.",             new BigDecimal("189.30")),
            new Stock("MSFT",  "Microsoft Corp.",        new BigDecimal("415.50")),
            new Stock("NVDA",  "NVIDIA Corp.",           new BigDecimal("875.40")),
            new Stock("AMZN",  "Amazon.com Inc.",        new BigDecimal("182.75")),
            new Stock("GOOGL", "Alphabet Inc.",          new BigDecimal("175.20")),
            new Stock("META",  "Meta Platforms Inc.",    new BigDecimal("505.60")),
            new Stock("BRK.B", "Berkshire Hathaway B",  new BigDecimal("395.10")),
            new Stock("LLY",   "Eli Lilly and Co.",     new BigDecimal("780.90")),
            new Stock("JPM",   "JPMorgan Chase & Co.",  new BigDecimal("198.45")),
            new Stock("TSLA",  "Tesla Inc.",             new BigDecimal("177.80"))
        );
    }
}
