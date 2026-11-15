package com.juzo.ai.ordermanager.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.dto.RiskDecision;
import com.juzo.ai.ordermanager.order.entity.Order;
import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.order.entity.OrderStatus;
import com.juzo.ai.ordermanager.order.entity.OrderType;
import com.juzo.ai.ordermanager.order.grpc.RiskGrpcClient;
import com.juzo.ai.ordermanager.order.repository.OrderRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Metrics are plain objects: a SimpleMeterRegistry lets us assert on them with no Spring context. */
class OrderServiceMetricsTest {

    private final OrderRepository repo = mock(OrderRepository.class);
    private final OrderEventPublisher publisher = mock(OrderEventPublisher.class);
    private final RiskGrpcClient risk = mock(RiskGrpcClient.class);
    private MeterRegistry registry;
    private OrderService service;

    private final PlaceOrderRequest request = new PlaceOrderRequest(
            UUID.randomUUID(), "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.TEN, null);

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new OrderService(repo, publisher, risk, registry);
        // placeOrder/cancelOrder register afterCommit hooks, which need an active synchronization
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private double placed(String outcome) {
        var c = registry.find("orders.placed").tag("outcome", outcome).tag("side", "BUY").tag("type", "MARKET").counter();
        return c == null ? 0 : c.count();
    }

    private double cancelled(String outcome) {
        var c = registry.find("orders.cancelled").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private Order savedOrder() {
        Date now = new Date();
        return new Order(UUID.randomUUID(), request.accountId(), "u1", "AAPL", OrderSide.BUY, OrderType.MARKET,
                BigDecimal.TEN, null, BigDecimal.ZERO, null, OrderStatus.PENDING, null, null, now, now);
    }

    // The availability/latency SLO rules (observability/prometheus/rules/slo.yml) query le="0.8" literally.
    @Test
    void latencyHistogramExposesTheExact800msSloBucket() {
        var prometheus = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        when(risk.check(any(), any(), any())).thenReturn(new RiskDecision(false, "NONE", "ok", "d1"));
        when(repo.save(any())).thenReturn(savedOrder());

        new OrderService(repo, publisher, risk, prometheus).placeOrder(request, "u1", "tok");

        assertThat(prometheus.scrape()).contains("orders_place_latency_seconds_bucket{")
                .contains("le=\"0.8\"");
    }

    @Test
    void approvedOrderCountsAsAcceptedAndRecordsLatency() {
        when(risk.check(any(), any(), any())).thenReturn(new RiskDecision(false, "NONE", "ok", "d1"));
        when(repo.save(any())).thenReturn(savedOrder());

        service.placeOrder(request, "u1", "tok");

        assertThat(placed("accepted")).isEqualTo(1);
        assertThat(registry.get("orders.place.latency").tag("outcome", "accepted").timer().count()).isEqualTo(1);
    }

    @Test
    void riskRuleRejectionCountsAsRejected() {
        when(risk.check(any(), any(), any())).thenReturn(new RiskDecision(true, "INSUFFICIENT_FUNDS", "no", "d2"));

        service.placeOrder(request, "u1", "tok");

        assertThat(placed("rejected")).isEqualTo(1);
        assertThat(placed("failsafe")).isZero();
    }

    @Test
    void riskUnavailableCountsAsFailsafe() {
        when(risk.check(any(), any(), any())).thenReturn(RiskDecision.systemReject("down"));

        service.placeOrder(request, "u1", "tok");

        assertThat(placed("failsafe")).isEqualTo(1);
        assertThat(placed("rejected")).isZero();
    }

    @Test
    void persistenceFailureRecordsErrorLatencyButNoPlacedCount() {
        when(risk.check(any(), any(), any())).thenReturn(new RiskDecision(false, "NONE", "ok", "d3"));
        when(repo.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.placeOrder(request, "u1", "tok")).isInstanceOf(IllegalStateException.class);

        assertThat(registry.find("orders.placed").counters()).isEmpty();
        assertThat(registry.get("orders.place.latency").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void cancelOutcomesAreCounted() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndUserId(id, "u1")).thenReturn(Optional.of(savedOrder()));
        when(repo.cancelIfCancellable(id)).thenReturn(1);
        service.cancelOrder(id, "u1");
        assertThat(cancelled("cancelled")).isEqualTo(1);

        UUID missing = UUID.randomUUID();
        when(repo.findByIdAndUserId(missing, "u1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelOrder(missing, "u1")).isInstanceOf(ResponseStatusException.class);
        assertThat(cancelled("not_found")).isEqualTo(1);

        UUID raced = UUID.randomUUID();
        when(repo.findByIdAndUserId(raced, "u1")).thenReturn(Optional.of(savedOrder()));
        when(repo.cancelIfCancellable(raced)).thenReturn(0);
        assertThatThrownBy(() -> service.cancelOrder(raced, "u1")).isInstanceOf(ResponseStatusException.class);
        assertThat(cancelled("conflict")).isEqualTo(1);
    }
}
