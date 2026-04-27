package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketDataServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private MarketDataService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        service = new MarketDataService(redisTemplate, "market.prices");
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void returnsAllTenInitialStocks() {
        assertThat(service.getStocks()).hasSize(10);
    }

    @Test
    void initialStocksContainExpectedTickers() {
        List<String> tickers = service.getStocks().stream().map(Stock::ticker).toList();

        assertThat(tickers).containsExactlyInAnyOrder(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "BRK.B", "LLY", "JPM", "TSLA"
        );
    }

    @Test
    void initialStockPricesArePositive() {
        assertThat(service.getStocks())
            .allSatisfy(stock -> assertThat(stock.price()).isPositive());
    }
}
