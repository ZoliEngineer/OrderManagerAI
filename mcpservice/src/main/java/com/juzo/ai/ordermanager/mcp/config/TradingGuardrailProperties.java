package com.juzo.ai.ordermanager.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Per-order limits applied to agent-initiated orders before they reach the Order Service.
 * A null limit disables that check.
 */
@ConfigurationProperties(prefix = "mcp.trading")
public record TradingGuardrailProperties(
        BigDecimal maxQuantityPerOrder,
        BigDecimal maxNotionalPerOrder
) {}
