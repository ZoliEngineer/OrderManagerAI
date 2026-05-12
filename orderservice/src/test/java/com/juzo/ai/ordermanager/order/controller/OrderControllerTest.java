package com.juzo.ai.ordermanager.order.controller;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.order.entity.OrderStatus;
import com.juzo.ai.ordermanager.order.entity.OrderType;
import com.juzo.ai.ordermanager.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Jwt jwt;

    @InjectMocks
    private OrderController controller;

    @Test
    void placeOrder_validRequest_returnsPendingResponse() {
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String userId = "user-123";

        PlaceOrderRequest request = new PlaceOrderRequest(
                accountId, "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.TEN, null
        );

        Order saved = new Order(orderId, accountId, userId, "AAPL",
                OrderSide.BUY, OrderType.MARKET, BigDecimal.TEN, null,
                BigDecimal.ZERO, null, OrderStatus.PENDING, null, 0L, null, null);

        when(jwt.getSubject()).thenReturn(userId);
        when(orderService.placeOrder(request, userId)).thenReturn(saved);

        OrderResponse response = controller.placeOrder(request, jwt);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo("PENDING");
    }
}
