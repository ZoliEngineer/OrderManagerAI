package com.juzo.ai.ordermanager.order.controller;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.service.OrderService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<Order>> getOrders(@RequestParam UUID accountId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        log.info("getOrders: accountId={} userId={}", accountId, userId);
        return ResponseEntity.ok(orderService.getOrdersForAccount(accountId, userId));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        String bearerToken = jwt.getTokenValue();
        log.info("placeOrder: accountId={} userId={} ticker={} side={} type={} quantity={} limitPrice={}",
            request.accountId(), userId, request.ticker(), request.side(),
            request.type(), request.quantity(), request.limitPrice());

        OrderResponse response = orderService.placeOrder(request, userId, bearerToken);
        if ("REJECTED".equals(response.status())) {
            return ResponseEntity.status(422).body(response);
        }
        return ResponseEntity.status(201).body(response);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable UUID orderId,
                                            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        log.info("cancelOrder: orderId={} userId={}", orderId, userId);
        orderService.cancelOrder(orderId, userId);
        return ResponseEntity.noContent().build();
    }
}
