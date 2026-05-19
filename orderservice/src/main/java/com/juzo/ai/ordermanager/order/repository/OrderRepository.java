package com.juzo.ai.ordermanager.order.repository;

import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.entity.OrderStatus;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends CrudRepository<Order, UUID> {

    List<Order> findByAccountIdAndUserId(UUID accountId, String userId);

    List<Order> findByStatus(OrderStatus status);

    Optional<Order> findByIdAndUserId(UUID id, String userId);

    @Modifying
    @Query("UPDATE orders SET status = 'CANCELLED', updated_at = NOW() WHERE id = :id AND status IN ('NEW', 'PENDING', 'PARTIALLY_FILLED')")
    int cancelIfCancellable(@Param("id") UUID id);
}
