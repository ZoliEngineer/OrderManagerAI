package com.juzo.ai.ordermanager.mcp.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Request body for the Order Service's {@code POST /api/orders}. */
public record PlaceOrderCommand(
        UUID accountId,
        String ticker,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal limitPrice
) {}
