package com.juzo.ai.ordermanager.order.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Order placeOrder(PlaceOrderRequest request, String userId) {
        checkRisk(request);
        Order order = persist(request, userId);
        eventPublisher.publishOrderCreated(order);
        return order;
    }

    private Order persist(PlaceOrderRequest request, String userId) {
        Order order = Order.create(
                request.accountId(), userId, request.ticker(),
                request.side(), request.type(),
                request.quantity(), request.limitPrice()
        );
        return orderRepository.save(order);
    }

    private void checkRisk(PlaceOrderRequest request) {
       // throw new UnsupportedOperationException("Not supported yet.");
    }
}
