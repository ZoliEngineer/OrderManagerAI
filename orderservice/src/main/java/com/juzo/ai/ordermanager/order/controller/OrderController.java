package com.juzo.ai.ordermanager.order.controller;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.service.OrderService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        log.info("placeOrder: accountId={} userId={} ticker={} side={} type={} quantity={} limitPrice={}",
            request.accountId(), userId, request.ticker(), request.side(),
            request.type(), request.quantity(), request.limitPrice());

        Order order = orderService.placeOrder(request, userId);
        return new OrderResponse(order.id(), order.status().name(), "Order accepted");
    }
}
