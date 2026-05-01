package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceTest {

    private static final BigDecimal Z = BigDecimal.ZERO;
    private static final List<Stock> INITIAL_STOCKS = List.of(
        new Stock("AAPL",  "Apple Inc.",            new BigDecimal("189.30"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("MSFT",  "Microsoft Corp.",       new BigDecimal("415.50"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("NVDA",  "NVIDIA Corp.",          new BigDecimal("875.40"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("AMZN",  "Amazon.com Inc.",       new BigDecimal("182.75"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("GOOGL", "Alphabet Inc.",         new BigDecimal("175.20"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("META",  "Meta Platforms Inc.",   new BigDecimal("505.60"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("BRK.B", "Berkshire Hathaway B", new BigDecimal("395.10"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("LLY",   "Eli Lilly and Co.",    new BigDecimal("780.90"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("JPM",   "JPMorgan Chase & Co.", new BigDecimal("198.45"), Z, Z, Z, Z, Z, Z, Z),
        new Stock("TSLA",  "Tesla Inc.",            new BigDecimal("177.80"), Z, Z, Z, Z, Z, Z, Z)
    );

    @Mock
    private PriceUpdateSource priceUpdateSource;

    private MarketDataService service;

    @BeforeEach
    void setUp() {
        when(priceUpdateSource.initialStocks()).thenReturn(INITIAL_STOCKS);
        service = new MarketDataService(priceUpdateSource, List.of());
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
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
