package com.juzo.ai.ordermanager.execution.engine;

import java.math.BigDecimal;
import java.util.UUID;

public record RestingOrder(
        UUID orderId,
        Side side,
        BigDecimal qty,
        BigDecimal remaining,
        BigDecimal limitPrice,
        long timestampNanos
) {}
