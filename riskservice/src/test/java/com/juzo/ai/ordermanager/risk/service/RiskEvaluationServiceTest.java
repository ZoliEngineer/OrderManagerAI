package com.juzo.ai.ordermanager.risk.service;

import com.juzo.ai.ordermanager.risk.grpc.RiskCheckRequest;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskEvaluationServiceTest {

    @Mock
    private BuyingPowerCache buyingPowerCache;

    @Mock
    private PriceCache priceCache;

    private RiskEvaluationService service;

    private static final String ACCOUNT_ID = UUID.randomUUID().toString();
    private static final String TICKER = "AAPL";

    @BeforeEach
    void setUp() {
        service = new RiskEvaluationService(buyingPowerCache, priceCache);
    }

    @Test
    void evaluate_marketBuyWithSufficientFunds_returnsApproved() {
        when(priceCache.get(TICKER)).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(buyingPowerCache.get(any(UUID.class))).thenReturn(Optional.of(new BigDecimal("2000.00")));

        RiskCheckResponse response = service.evaluate(marketBuyRequest("10"));

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.APPROVED);
        assertThat(response.getDecisionId()).isNotBlank();
    }

    @Test
    void evaluate_limitBuyUsesLimitPriceNotCache_returnsApproved() {
        // price cache should not be consulted for LIMIT orders
        when(buyingPowerCache.get(any(UUID.class))).thenReturn(Optional.of(new BigDecimal("500.00")));

        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setUserId("user-1")
                .setTicker(TICKER)
                .setSide(RiskCheckRequest.Side.BUY)
                .setType(RiskCheckRequest.Type.LIMIT)
                .setQuantity("5")
                .setLimitPrice("90.00")
                .build();

        RiskCheckResponse response = service.evaluate(request);

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.APPROVED);
    }

    @Test
    void evaluate_marketBuyWithInsufficientFunds_returnsRejectedWithInsufficientFunds() {
        when(priceCache.get(TICKER)).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(buyingPowerCache.get(any(UUID.class))).thenReturn(Optional.of(new BigDecimal("500.00")));

        RiskCheckResponse response = service.evaluate(marketBuyRequest("10")); // order value = 1000

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.REJECTED);
        assertThat(response.getReason()).isEqualTo(RiskCheckResponse.ReasonCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void evaluate_marketBuyWithNoCachedPrice_returnsRejectedWithPriceUnavailable() {
        when(priceCache.get(TICKER)).thenReturn(Optional.empty());

        RiskCheckResponse response = service.evaluate(marketBuyRequest("10"));

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.REJECTED);
        assertThat(response.getReason()).isEqualTo(RiskCheckResponse.ReasonCode.PRICE_UNAVAILABLE);
    }

    @Test
    void evaluate_marketBuyWithNoCachedBuyingPower_returnsRejectedWithBuyingPowerUnavailable() {
        when(priceCache.get(TICKER)).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(buyingPowerCache.get(any(UUID.class))).thenReturn(Optional.empty());

        RiskCheckResponse response = service.evaluate(marketBuyRequest("10"));

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.REJECTED);
        assertThat(response.getReason()).isEqualTo(RiskCheckResponse.ReasonCode.BUYING_POWER_UNAVAILABLE);
    }

    @Test
    void evaluate_sellOrder_returnsRejectedWithInsufficientShares() {
        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setUserId("user-1")
                .setTicker(TICKER)
                .setSide(RiskCheckRequest.Side.SELL)
                .setType(RiskCheckRequest.Type.MARKET)
                .setQuantity("5")
                .build();

        RiskCheckResponse response = service.evaluate(request);

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.REJECTED);
        assertThat(response.getReason()).isEqualTo(RiskCheckResponse.ReasonCode.INSUFFICIENT_SHARES);
    }

    private RiskCheckRequest marketBuyRequest(String quantity) {
        return RiskCheckRequest.newBuilder()
                .setAccountId(ACCOUNT_ID)
                .setUserId("user-1")
                .setTicker(TICKER)
                .setSide(RiskCheckRequest.Side.BUY)
                .setType(RiskCheckRequest.Type.MARKET)
                .setQuantity(quantity)
                .build();
    }
}
