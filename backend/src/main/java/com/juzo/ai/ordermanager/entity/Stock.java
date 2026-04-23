package com.juzo.ai.ordermanager.entity;

import java.math.BigDecimal;

public record Stock(
        String ticker,
        String name,
        BigDecimal price
) {

}
