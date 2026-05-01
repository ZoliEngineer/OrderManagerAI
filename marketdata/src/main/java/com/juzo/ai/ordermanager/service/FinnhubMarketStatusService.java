package com.juzo.ai.ordermanager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.juzo.ai.ordermanager.entity.MarketStatus;

import reactor.core.publisher.Mono;

@Service
@ConditionalOnProperty(name = "finnhub.api-key")
public class FinnhubMarketStatusService {

    private static final Logger log = LoggerFactory.getLogger(FinnhubMarketStatusService.class);

    private final WebClient webClient;

    public FinnhubMarketStatusService(WebClient finnhubRestClient) {
        this.webClient = finnhubRestClient;
    }

    public Mono<MarketStatus> getMarketStatus() {
        return webClient.get()
            .uri("/stock/market-status?exchange=US")
            .retrieve()
            .bodyToMono(MarketStatus.class)
            .doOnError(e -> log.warn("Failed to fetch market status from Finnhub: {}", e.getMessage()));
    }
}
