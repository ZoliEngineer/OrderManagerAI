package com.juzo.ai.ordermanager.order.dto;

import com.juzo.ai.ordermanager.risk.grpc.RiskCheckResponse;

public record RiskDecision(
        boolean rejected,
        String reason,
        String message,
        String decisionId
) {
    public static RiskDecision from(RiskCheckResponse response) {
        boolean rejected = response.getDecision() == RiskCheckResponse.Decision.REJECTED;
        return new RiskDecision(
                rejected,
                response.getReason().name(),
                response.getMessage(),
                response.getDecisionId()
        );
    }

    /** True when the rejection came from our own fail-safe (Risk unreachable), not from a risk rule. */
    public boolean failSafe() {
        return "system".equals(decisionId);
    }

    public static RiskDecision systemReject(String message) {
        return new RiskDecision(true, "BUYING_POWER_UNAVAILABLE", message, "system");
    }
}
