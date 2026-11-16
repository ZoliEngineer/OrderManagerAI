package com.juzo.ai.ordermanager.risk.grpc;

import com.juzo.ai.ordermanager.risk.service.RiskEvaluationService;
import io.grpc.Status;
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
        long start = System.nanoTime();
        RiskCheckResponse response;
        try {
            response = riskEvaluationService.evaluate(request);
        } catch (RuntimeException e) {
            // e.g. Redis down. Without this the caller only sees UNKNOWN and Risk logs nothing.
            log.error("Risk evaluation failed: account={} ticker={} side={} type={} qty={}",
                    request.getAccountId(), request.getTicker(), request.getSide(), request.getType(),
                    request.getQuantity(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("Risk evaluation failed").asRuntimeException());
            return;
        }

        // One line per decision with everything needed to explain it. decisionId is also returned to the caller.
        log.info("Risk decision={} reason={} decisionId={} account={} ticker={} side={} type={} qty={} elapsedMs={} message={}",
                response.getDecision(), response.getReason(), response.getDecisionId(), request.getAccountId(),
                request.getTicker(), request.getSide(), request.getType(), request.getQuantity(),
                (System.nanoTime() - start) / 1_000_000, response.getMessage());

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
