package com.juzo.ai.ordermanager.order.controller;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        log.info("placeOrder: accountId={} ticker={} side={} type={} quantity={} limitPrice={}",
            request.accountId(), request.ticker(), request.side(),
            request.type(), request.quantity(), request.limitPrice());

        return new OrderResponse(UUID.randomUUID(), "PENDING", "Order accepted");
    }
}
