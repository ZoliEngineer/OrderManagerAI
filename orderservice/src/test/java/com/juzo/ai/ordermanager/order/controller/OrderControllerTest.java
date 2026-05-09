package com.juzo.ai.ordermanager.order.controller;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.order.entity.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderControllerTest {

    private final OrderController controller = new OrderController();

    @Test
    void placeOrder_validRequest_returnsPendingResponse() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                UUID.randomUUID(),
                "AAPL",
                OrderSide.BUY,
                OrderType.MARKET,
                BigDecimal.TEN,
                null
        );

        OrderResponse response = controller.placeOrder(request);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.orderId()).isNotNull();
    }
}
