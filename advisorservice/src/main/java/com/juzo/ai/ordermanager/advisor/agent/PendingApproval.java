package com.juzo.ai.ordermanager.advisor.agent;

import com.juzo.ai.ordermanager.advisor.mcp.ToolCallOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tool calls from one assistant turn that are waiting for the user. Calls that needed no approval have
 * already run and carry their outcome, so all results go back to Claude together in one message.
 */
public record PendingApproval(List<PendingCall> calls, Instant expiresAt) {

    public record PendingCall(String toolUseId, String name, Map<String, Object> input, String summary,
                              boolean requiresApproval, ToolCallOutcome autoOutcome) {}

    public List<PendingCall> awaitingDecision() {
        return calls.stream().filter(PendingCall::requiresApproval).toList();
    }
}
