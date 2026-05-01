package com.juzo.ai.ordermanager.controller;

import com.juzo.ai.ordermanager.entity.MarketStatus;
import com.juzo.ai.ordermanager.entity.Stock;
import com.juzo.ai.ordermanager.service.FinnhubMarketStatusService;
import com.juzo.ai.ordermanager.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataControllerTest {

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private FinnhubMarketStatusService marketStatusService;

    @Test
    void returnsOkWithStocksWhenDataAvailable() {
        MarketDataController controller = new MarketDataController(marketDataService, Optional.empty());
        BigDecimal z = BigDecimal.ZERO;
        List<Stock> stocks = List.of(
            new Stock("AAPL", "Apple Inc.", new BigDecimal("189.30"), z, z, z, z, z, z, z)
        );
        when(marketDataService.getStocks()).thenReturn(stocks);

        ResponseEntity<List<Stock>> response = controller.getMarketData();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(stocks);
    }

    @Test
    void returnsNoContentWhenStockListIsEmpty() {
        MarketDataController controller = new MarketDataController(marketDataService, Optional.empty());
        when(marketDataService.getStocks()).thenReturn(List.of());

        ResponseEntity<List<Stock>> response = controller.getMarketData();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void returnsMarketStatusFromFinnhub() {
        MarketDataController controller = new MarketDataController(marketDataService, Optional.of(marketStatusService));
        MarketStatus open = new MarketStatus("US", null, true, "regular", "America/New_York", 1697018041L);
        when(marketStatusService.getMarketStatus()).thenReturn(Mono.just(open));

        ResponseEntity<MarketStatus> response = controller.getMarketStatus().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(open);
    }

    @Test
    void returnsMarketStatusFallbackWhenServiceAbsent() {
        MarketDataController controller = new MarketDataController(marketDataService, Optional.empty());

        ResponseEntity<MarketStatus> response = controller.getMarketStatus().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isOpen()).isFalse();
        assertThat(response.getBody().session()).isEqualTo("unavailable");
    }

    @Test
    void returnsMarketStatusFallbackOnFinnhubError() {
        MarketDataController controller = new MarketDataController(marketDataService, Optional.of(marketStatusService));
        when(marketStatusService.getMarketStatus()).thenReturn(Mono.error(new RuntimeException("timeout")));

        ResponseEntity<MarketStatus> response = controller.getMarketStatus().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isOpen()).isFalse();
        assertThat(response.getBody().session()).isEqualTo("unavailable");
    }
}
