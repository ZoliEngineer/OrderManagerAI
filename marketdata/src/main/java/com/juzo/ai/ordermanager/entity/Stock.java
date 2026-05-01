package com.juzo.ai.ordermanager.entity;

import java.math.BigDecimal;

public record Stock(
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
) {

}
