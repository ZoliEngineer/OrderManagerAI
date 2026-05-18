package com.juzo.ai.ordermanager.risk.grpc;

import com.juzo.ai.ordermanager.risk.service.RiskEvaluationService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class RiskCheckGrpcService extends RiskCheckGrpc.RiskCheckImplBase {

    private static final Logger log = LoggerFactory.getLogger(RiskCheckGrpcService.class);

    private final RiskEvaluationService riskEvaluationService;

    public RiskCheckGrpcService(RiskEvaluationService riskEvaluationService) {
        this.riskEvaluationService = riskEvaluationService;
    }

    @Override
    public void check(RiskCheckRequest request, StreamObserver<RiskCheckResponse> responseObserver) {
        log.info("Risk check: account={} user={} ticker={} side={} type={} qty={}",
                request.getAccountId(), request.getUserId(), request.getTicker(),
                request.getSide(), request.getType(), request.getQuantity());

        RiskCheckResponse response = riskEvaluationService.evaluate(request);

        log.info("Risk decision={} reason={} account={} ticker={}",
                response.getDecision(), response.getReason(),
                request.getAccountId(), request.getTicker());

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
