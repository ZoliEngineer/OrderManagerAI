package com.juzo.ai.ordermanager.mcp.dto;

import java.math.BigDecimal;

/** Mirrors the Market Data service's {@code Stock} payload. */
public record StockPrice(
        String ticker,
        String name,
        BigDecimal price,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal prevClose,
        BigDecimal totalChange,
        BigDecimal changePercent,
        BigDecimal lastChange
) {}
