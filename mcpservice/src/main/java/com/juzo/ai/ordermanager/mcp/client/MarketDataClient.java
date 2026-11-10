package com.juzo.ai.ordermanager.mcp.client;

import com.juzo.ai.ordermanager.mcp.dto.StockPrice;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class MarketDataClient {

    private static final ParameterizedTypeReference<List<StockPrice>> STOCK_LIST = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public MarketDataClient(@Qualifier("marketDataRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** Current snapshot of all tracked tickers; empty when the service has no prices yet (204). */
    public List<StockPrice> getPrices(String bearerToken) {
        try {
            List<StockPrice> prices = restClient.get()
                    .uri("/api/marketdata")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(STOCK_LIST);
            return prices == null ? List.of() : prices;
        } catch (RestClientResponseException e) {
            throw new DownstreamException(e.getStatusCode().value(),
                    "Market Data service returned HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new DownstreamException("Market Data service unavailable", e);
        }
    }
}
