package com.juzo.ai.ordermanager.mcp.dto;

import java.util.UUID;

/**
 * Outcome of {@code place_order}. {@code accepted=false} is a normal business result
 * (e.g. rejected by the risk check or a guardrail), not a tool failure.
 */
public record PlaceOrderResult(boolean accepted, UUID orderId, String status, String message) {

    public static PlaceOrderResult rejected(String message) {
        return new PlaceOrderResult(false, null, "REJECTED", message);
    }
}
