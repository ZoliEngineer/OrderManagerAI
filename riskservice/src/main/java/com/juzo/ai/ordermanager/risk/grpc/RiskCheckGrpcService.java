package com.juzo.ai.ordermanager.risk.grpc;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@GrpcService
public class RiskCheckGrpcService extends RiskCheckGrpc.RiskCheckImplBase {

    private static final Logger log = LoggerFactory.getLogger(RiskCheckGrpcService.class);

    @Override
    public void check(RiskCheckRequest request, StreamObserver<RiskCheckResponse> responseObserver) {
        log.info("Risk check: account={} user={} ticker={} side={} type={}",
                request.getAccountId(), request.getUserId(), request.getTicker(),
                request.getSide(), request.getType());
        RiskCheckResponse response = RiskCheckResponse.newBuilder()
                .setDecision(RiskCheckResponse.Decision.REJECTED)
                .setReason(RiskCheckResponse.ReasonCode.INVALID_REQUEST)
                .setMessage("Risk evaluation not yet implemented — all orders are temporarily rejected.")
                .setDecisionId(UUID.randomUUID().toString())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
