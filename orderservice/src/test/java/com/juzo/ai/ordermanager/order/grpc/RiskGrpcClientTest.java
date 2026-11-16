package com.juzo.ai.ordermanager.order.grpc;

import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.dto.RiskDecision;
import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.order.entity.OrderType;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGrpcClientTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
    }

    @Test
    void unreachableRisk_failsSafeWithItsOwnReasonAndTheGrpcStatusAsCause() {
        // Nothing listens on port 1, so the call fails with UNAVAILABLE (a real gRPC status, not a mock).
        channel = ManagedChannelBuilder.forAddress("localhost", 1).usePlaintext().build();
        RiskGrpcClient client = new RiskGrpcClient(meters);
        ReflectionTestUtils.setField(client, "stub", RiskCheckGrpc.newBlockingStub(channel));

        RiskDecision decision = client.check(
                new PlaceOrderRequest(UUID.randomUUID(), "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.TEN, null),
                "user-1", "token");

        assertThat(decision.rejected()).isTrue();
        assertThat(decision.failSafe()).isTrue();
        // Not BUYING_POWER_UNAVAILABLE: that code means a genuine cache miss inside Risk.
        assertThat(decision.reason()).isEqualTo("RISK_UNAVAILABLE");
        assertThat(decision.message()).contains("UNAVAILABLE");
        assertThat(meters.get("orders.risk.failsafe").tag("cause", "UNAVAILABLE").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("orders.risk.call.latency").tag("outcome", "failsafe").timer().count()).isEqualTo(1L);
    }
}
