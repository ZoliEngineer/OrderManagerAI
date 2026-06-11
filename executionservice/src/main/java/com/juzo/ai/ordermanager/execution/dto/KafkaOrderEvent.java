package com.juzo.ai.ordermanager.execution.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KafkaOrderEvent(String eventType, KafkaOrder order) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KafkaOrder(
            String id,
            String ticker,
            String side,
            String type,
            BigDecimal limitPrice,
            BigDecimal quantity
    ) {}
}
