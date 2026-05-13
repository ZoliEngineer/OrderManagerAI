package com.juzo.ai.ordermanager.risk.grpc;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.UUID;

@GrpcService
public class RiskCheckGrpcService extends RiskCheckGrpc.RiskCheckImplBase {

    @Override
    public void check(RiskCheckRequest request, StreamObserver<RiskCheckResponse> responseObserver) {
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
