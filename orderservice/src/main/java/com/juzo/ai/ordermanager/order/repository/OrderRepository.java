package com.juzo.ai.ordermanager.order.repository;

import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.entity.OrderStatus;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

public interface OrderRepository extends CrudRepository<Order, UUID> {

    List<Order> findByAccountIdAndUserId(UUID accountId, String userId);

    List<Order> findByStatus(OrderStatus status);
}
