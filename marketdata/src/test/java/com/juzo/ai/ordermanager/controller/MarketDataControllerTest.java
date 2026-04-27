package com.juzo.ai.ordermanager.controller;

import com.juzo.ai.ordermanager.entity.Stock;
import com.juzo.ai.ordermanager.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataControllerTest {

    @Mock
    private MarketDataService marketDataService;

    @InjectMocks
    private MarketDataController controller;

    @Test
    void returnsOkWithStocksWhenDataAvailable() {
        List<Stock> stocks = List.of(
            new Stock("AAPL", "Apple Inc.", new BigDecimal("189.30"), BigDecimal.ZERO)
        );
        when(marketDataService.getStocks()).thenReturn(stocks);

        ResponseEntity<List<Stock>> response = controller.getMarketData();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(stocks);
    }

    @Test
    void returnsNoContentWhenStockListIsEmpty() {
        when(marketDataService.getStocks()).thenReturn(List.of());

        ResponseEntity<List<Stock>> response = controller.getMarketData();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }
}
