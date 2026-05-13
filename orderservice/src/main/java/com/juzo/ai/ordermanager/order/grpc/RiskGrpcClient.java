package com.juzo.ai.ordermanager.order.grpc;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.juzo.ai.ordermanager.order.dto.PlaceOrderRequest;
import com.juzo.ai.ordermanager.order.dto.RiskDecision;
import com.juzo.ai.ordermanager.order.entity.OrderSide;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckGrpc;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckRequest;
import com.juzo.ai.ordermanager.risk.grpc.RiskCheckResponse;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class RiskGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(RiskGrpcClient.class);
    private static final Metadata.Key<String> AUTH_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcClient("risk-service")
    private RiskCheckGrpc.RiskCheckBlockingStub stub;

    public RiskDecision check(PlaceOrderRequest req, String userId, String bearerToken) {
        try {
            Metadata md = new Metadata();
            md.put(AUTH_KEY, "Bearer " + bearerToken);
            RiskCheckResponse response = stub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md))
                    .withDeadlineAfter(800, TimeUnit.MILLISECONDS)
                    .check(toProto(req, userId));
            return RiskDecision.from(response);
        } catch (Exception ex) {
            log.error("Risk Service unavailable — failing safe: {}", ex);
            return RiskDecision.systemReject("Risk Service unavailable");
        }
    }

    private RiskCheckRequest toProto(PlaceOrderRequest req, String userId) {
        RiskCheckRequest.Builder builder = RiskCheckRequest.newBuilder()
                .setAccountId(req.accountId().toString())
                .setUserId(userId)
                .setTicker(req.ticker())
                .setSide(req.side() == OrderSide.BUY
                        ? RiskCheckRequest.Side.BUY
                        : RiskCheckRequest.Side.SELL)
                .setType(switch (req.type()) {
                    case MARKET -> RiskCheckRequest.Type.MARKET;
                    case LIMIT  -> RiskCheckRequest.Type.LIMIT;
                })
                .setQuantity(req.quantity().toPlainString());
        if (req.limitPrice() != null) {
            builder.setLimitPrice(req.limitPrice().toPlainString());
        }
        return builder.build();
    }
}
