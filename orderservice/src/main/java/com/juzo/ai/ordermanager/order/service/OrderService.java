package com.juzo.ai.ordermanager.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.dto.RiskDecision;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.grpc.RiskGrpcClient;
import com.juzo.ai.ordermanager.order.repository.OrderRepository;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final RiskGrpcClient riskGrpcClient;

    public OrderService(OrderRepository orderRepository,
                        OrderEventPublisher eventPublisher,
                        RiskGrpcClient riskGrpcClient) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.riskGrpcClient = riskGrpcClient;
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request, String userId, String bearerToken) {
        RiskDecision decision = checkRisk(request, userId, bearerToken);
        if (decision.rejected()) {
            return new OrderResponse(null, "REJECTED", decision.reason() + " — " + decision.message());
        }
        Order order = persist(request, userId);
        eventPublisher.publishOrderCreated(order);
        return new OrderResponse(order.id(), order.status().name(), "Order accepted");
    }

    private Order persist(PlaceOrderRequest request, String userId) {
        Order order = Order.create(
                request.accountId(), userId, request.ticker(),
                request.side(), request.type(),
                request.quantity(), request.limitPrice()
        );
        return orderRepository.save(order);
    }

    private RiskDecision checkRisk(PlaceOrderRequest request, String userId, String bearerToken) {
        RiskDecision decision = riskGrpcClient.check(request, userId, bearerToken);
        if (decision.rejected()) {
            log.warn("Order rejected by risk check: reason={} message={}", decision.reason(), decision.message());
        }
        return decision;
    }
}
