package com.juzo.ai.ordermanager.risk.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCheckGrpcServiceTest {

    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new RiskCheckGrpcService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void check_returnsRejectedWithInvalidRequest() {
        RiskCheckGrpc.RiskCheckBlockingStub stub = RiskCheckGrpc.newBlockingStub(channel);

        RiskCheckRequest request = RiskCheckRequest.newBuilder()
                .setAccountId("acc-1")
                .setUserId("user-1")
                .setTicker("AAPL")
                .setSide(RiskCheckRequest.Side.BUY)
                .setType(RiskCheckRequest.Type.MARKET)
                .setQuantity("10")
                .build();

        RiskCheckResponse response = stub.check(request);

        assertThat(response.getDecision()).isEqualTo(RiskCheckResponse.Decision.REJECTED);
        assertThat(response.getReason()).isEqualTo(RiskCheckResponse.ReasonCode.INVALID_REQUEST);
        assertThat(response.getDecisionId()).isNotBlank();
    }
}
