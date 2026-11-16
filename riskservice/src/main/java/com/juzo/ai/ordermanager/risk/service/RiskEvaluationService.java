package com.juzo.ai.ordermanager.risk.service;

import com.juzo.ai.ordermanager.risk.grpc.RiskCheckRequest;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class RiskEvaluationService {

    private final BuyingPowerCache buyingPowerCache;
    private final PriceCache priceCache;
    private final MeterRegistry meters;
    private final Timer latency;

    public RiskEvaluationService(BuyingPowerCache buyingPowerCache, PriceCache priceCache, MeterRegistry meters) {
        this.buyingPowerCache = buyingPowerCache;
        this.priceCache = priceCache;
        this.meters = meters;
        this.latency = Timer.builder("risk.evaluate.latency")
                .description("Time to evaluate one pre-trade risk check (cache lookups + arithmetic)")
                .publishPercentileHistogram()
                .serviceLevelObjectives(java.time.Duration.ofMillis(50))   // KPI: risk check < 50 ms
                .register(meters);
    }

    public RiskCheckResponse evaluate(RiskCheckRequest request) {
        RiskCheckResponse response = latency.record(() -> doEvaluate(request));
        // decision and reason are enums => bounded tag values
        Counter.builder("risk.checks")
                .description("Risk decisions by outcome and reason code")
                .tag("decision", response.getDecision().name().toLowerCase())
                .tag("reason", response.getReason().name().toLowerCase())
                .register(meters)
                .increment();
        return response;
    }

    private RiskCheckResponse doEvaluate(RiskCheckRequest request) {
        String decisionId = UUID.randomUUID().toString();
        try {
            if (request.getSide() == RiskCheckRequest.Side.BUY) {
                return evaluateBuy(request, decisionId);
            }
            // Position cache not implemented — reject all sells until holdings data is available
            return buildRejected(RiskCheckResponse.ReasonCode.INSUFFICIENT_SHARES,
                    "No position data available to validate sell order", decisionId);
        } catch (IllegalArgumentException e) {
            return buildRejected(RiskCheckResponse.ReasonCode.INVALID_REQUEST,
                    "Invalid request: " + e.getMessage(), decisionId);
        }
    }

    private RiskCheckResponse evaluateBuy(RiskCheckRequest request, String decisionId) {
        BigDecimal quantity = new BigDecimal(request.getQuantity());

        Optional<BigDecimal> effectivePrice = resolveEffectivePrice(request);
        if (effectivePrice.isEmpty()) {
            return buildRejected(RiskCheckResponse.ReasonCode.PRICE_UNAVAILABLE,
                    "No market price cached for " + request.getTicker(), decisionId);
        }

        BigDecimal orderValue = quantity.multiply(effectivePrice.get());
        return checkBuyingPower(UUID.fromString(request.getAccountId()), orderValue, decisionId);
    }

    private Optional<BigDecimal> resolveEffectivePrice(RiskCheckRequest request) {
        if (request.getType() == RiskCheckRequest.Type.LIMIT) {
            return Optional.of(new BigDecimal(request.getLimitPrice()));
        }
        return priceCache.get(request.getTicker());
    }

    private RiskCheckResponse checkBuyingPower(UUID accountId, BigDecimal orderValue, String decisionId) {
        Optional<BigDecimal> buyingPower = buyingPowerCache.get(accountId);
        if (buyingPower.isEmpty()) {
            return buildRejected(RiskCheckResponse.ReasonCode.BUYING_POWER_UNAVAILABLE,
                    "Buying power not cached for account " + accountId, decisionId);
        }
        if (buyingPower.get().compareTo(orderValue) < 0) {
            return buildRejected(RiskCheckResponse.ReasonCode.INSUFFICIENT_FUNDS,
                    "Order value " + orderValue + " exceeds buying power " + buyingPower.get(), decisionId);
        }
        return buildApproved(decisionId);
    }

    private static RiskCheckResponse buildApproved(String decisionId) {
        return RiskCheckResponse.newBuilder()
                .setDecision(RiskCheckResponse.Decision.APPROVED)
                .setReason(RiskCheckResponse.ReasonCode.REASON_UNSPECIFIED)
                .setDecisionId(decisionId)
                .build();
    }

    private static RiskCheckResponse buildRejected(RiskCheckResponse.ReasonCode reason, String message, String decisionId) {
        return RiskCheckResponse.newBuilder()
                .setDecision(RiskCheckResponse.Decision.REJECTED)
                .setReason(reason)
                .setMessage(message)
                .setDecisionId(decisionId)
                .build();
    }
}
