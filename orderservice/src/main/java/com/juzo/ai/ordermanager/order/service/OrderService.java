package com.juzo.ai.ordermanager.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.juzo.ai.ordermanager.order.dto.OrderResponse;
import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.dto.RiskDecision;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.entity.OrderStatus;
import com.juzo.ai.ordermanager.order.grpc.RiskGrpcClient;
import com.juzo.ai.ordermanager.order.repository.OrderRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final Set<OrderStatus> CANCELLABLE_STATUSES =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

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

    public List<Order> getOrdersForAccount(UUID accountId, String userId) {
        return orderRepository.findByAccountIdAndUserId(accountId, userId);
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request, String userId, String bearerToken) {
        RiskDecision decision = checkRisk(request, userId, bearerToken);
        if (decision.rejected()) {
            return new OrderResponse(null, "REJECTED", decision.reason() + " — " + decision.message());
        }
        Order order = persist(request, userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishOrderCreated(order);
            }
        });
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

    @Transactional
    public void cancelOrder(UUID orderId, String userId) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId));

        if (!CANCELLABLE_STATUSES.contains(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be cancelled in status: " + order.status());
        }

        int updated = orderRepository.cancelIfCancellable(orderId);
        if (updated == 0) {
            // Status changed concurrently between the check and the UPDATE
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be cancelled in status: " + order.status());
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishOrderCancelled(order);
            }
        });
    }

    private RiskDecision checkRisk(PlaceOrderRequest request, String userId, String bearerToken) {
        RiskDecision decision = riskGrpcClient.check(request, userId, bearerToken);
        if (decision.rejected()) {
            log.warn("Order rejected by risk check: reason={} message={}", decision.reason(), decision.message());
        }
        return decision;
    }
}
