package com.juzo.ai.ordermanager.order.controller;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.order.entity.OrderType;
import com.juzo.ai.ordermanager.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
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
    void placeOrder_validRequest_returns201WithPendingResponse() {
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String userId = "user-123";
        String bearerToken = "test-token";

        PlaceOrderRequest request = new PlaceOrderRequest(
                accountId, "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.TEN, null
        );

        when(jwt.getSubject()).thenReturn(userId);
        when(jwt.getTokenValue()).thenReturn(bearerToken);
        when(orderService.placeOrder(request, userId, bearerToken))
                .thenReturn(new OrderResponse(orderId, "PENDING", "Order accepted"));

        ResponseEntity<OrderResponse> result = controller.placeOrder(request, jwt);

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody().orderId()).isEqualTo(orderId);
        assertThat(result.getBody().status()).isEqualTo("PENDING");
    }

    @Test
    void placeOrder_riskRejected_returns422WithReason() {
        UUID accountId = UUID.randomUUID();
        String userId = "user-123";
        String bearerToken = "test-token";

        PlaceOrderRequest request = new PlaceOrderRequest(
                accountId, "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.TEN, null
        );

        when(jwt.getSubject()).thenReturn(userId);
        when(jwt.getTokenValue()).thenReturn(bearerToken);
        when(orderService.placeOrder(request, userId, bearerToken))
                .thenReturn(new OrderResponse(null, "REJECTED", "INSUFFICIENT_FUNDS — Balance too low"));

        ResponseEntity<OrderResponse> result = controller.placeOrder(request, jwt);

        assertThat(result.getStatusCode().value()).isEqualTo(422);
        assertThat(result.getBody().status()).isEqualTo("REJECTED");
        assertThat(result.getBody().message()).contains("INSUFFICIENT_FUNDS");
    }
}
