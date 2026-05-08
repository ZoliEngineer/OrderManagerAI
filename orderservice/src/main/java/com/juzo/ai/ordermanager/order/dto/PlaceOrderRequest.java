package com.juzo.ai.ordermanager.order.dto;

import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.order.entity.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record PlaceOrderRequest(
    @NotNull UUID accountId,
    @NotBlank @Size(max = 10) String ticker,
    @NotNull OrderSide side,
    @NotNull OrderType type,
    @NotNull @DecimalMin("0.0001") BigDecimal quantity,
    BigDecimal limitPrice  // required for LIMIT, validated in service
) {}
