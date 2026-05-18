package com.juzo.ai.ordermanager.risk.service;

import com.juzo.ai.ordermanager.risk.grpc.RiskCheckRequest;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class RiskEvaluationService {

    private final BuyingPowerCache buyingPowerCache;
    private final PriceCache priceCache;

    public RiskEvaluationService(BuyingPowerCache buyingPowerCache, PriceCache priceCache) {
        this.buyingPowerCache = buyingPowerCache;
        this.priceCache = priceCache;
    }

    public RiskCheckResponse evaluate(RiskCheckRequest request) {
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
