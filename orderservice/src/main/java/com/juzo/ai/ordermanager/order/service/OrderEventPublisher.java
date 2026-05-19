package com.juzo.ai.ordermanager.order.service;

import com.juzo.ai.ordermanager.order.entity.Order;

public interface OrderEventPublisher {

    void publishOrderCreated(Order order);

    void publishOrderCancelled(Order order);
}
