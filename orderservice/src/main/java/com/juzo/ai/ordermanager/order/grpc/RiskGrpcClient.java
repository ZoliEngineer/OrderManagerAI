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
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class RiskGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(RiskGrpcClient.class);
    private static final Metadata.Key<String> AUTH_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcClient("risk-service")
    private RiskCheckGrpc.RiskCheckBlockingStub stub;

    private final MeterRegistry meters;

    public RiskGrpcClient(MeterRegistry meters) {
        this.meters = meters;
    }

    public RiskDecision check(PlaceOrderRequest req, String userId, String bearerToken) {
        long start = System.nanoTime();
        try {
            Metadata md = new Metadata();
            md.put(AUTH_KEY, "Bearer " + bearerToken);
            RiskCheckResponse response = stub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md))
                    .withDeadlineAfter(800, TimeUnit.MILLISECONDS)
                    .check(toProto(req, userId));
            RiskDecision decision = RiskDecision.from(response);
            recordCall(decision.rejected() ? "rejected" : "approved", start);
            return decision;
        } catch (Exception ex) {
            // The gRPC status code says why: DEADLINE_EXCEEDED (slow or cold start), UNAVAILABLE (down / not reachable),
            // UNAUTHENTICATED (token), INTERNAL / UNKNOWN (Risk threw). It is a bounded set, so it is safe as a tag.
            String cause = ex instanceof StatusRuntimeException sre ? sre.getStatus().getCode().name() : "OTHER";
            long elapsedMs = recordCall("failsafe", start);
            Counter.builder("orders.risk.failsafe")
                    .description("Orders rejected because the Risk Service call failed, by gRPC status code")
                    .tag("cause", cause)
                    .register(meters)
                    .increment();
            log.warn("Risk call failed, failing safe (order rejected): cause={} elapsedMs={} account={} ticker={}",
                    cause, elapsedMs, req.accountId(), req.ticker(), ex);
            return RiskDecision.systemReject("Risk Service call failed (" + cause + " after " + elapsedMs + " ms)");
        }
    }

    /** Round-trip time of the Risk call by result, so slow calls are visible even when they still succeed. */
    private long recordCall(String outcome, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        Timer.builder("orders.risk.call.latency")
                .description("Order -> Risk gRPC round trip, by result (deadline is 800 ms)")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .serviceLevelObjectives(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(800))
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        return TimeUnit.NANOSECONDS.toMillis(nanos);
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
