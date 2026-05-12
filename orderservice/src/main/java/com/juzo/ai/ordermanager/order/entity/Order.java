package com.juzo.ai.ordermanager.order.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

@Table("orders")
public record Order(
        @Id UUID id,
        UUID accountId,
        String userId,
        String ticker,
        OrderSide side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal limitPrice,
        BigDecimal filledQuantity,
        BigDecimal avgFillPrice,
        OrderStatus status,
        String rejectionReason,
        @Version Long version,
        Date createdAt,
        Date updatedAt
) {
    public static Order create(UUID accountId, String userId, String ticker,
                               OrderSide side, OrderType type,
                               BigDecimal quantity, BigDecimal limitPrice) {
        Date now = new Date();
        return new Order(null, accountId, userId, ticker, side, type, quantity, limitPrice,
                BigDecimal.ZERO, null, OrderStatus.PENDING, null, null, now, now);
    }
}
