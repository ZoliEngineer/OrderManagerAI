package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.juzo.ai.ordermanager.advisor.agent.PendingApproval.PendingCall;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import com.juzo.ai.ordermanager.advisor.mcp.McpToolGateway;
import com.juzo.ai.ordermanager.advisor.mcp.ToolCallOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The advisor's agent loop: a manual Claude tool-use loop over the MCP trading tools plus server-side web search.
 * Read-only tools run immediately; any other tool call pauses the turn until the user approves or rejects it.
 * Callers must hold the conversation's lock ({@link Conversation#tryAcquire()}).
 */
@Component
public class AdvisorAgent {

    private static final Logger log = LoggerFactory.getLogger(AdvisorAgent.class);
    /** Structured trail of every approval decision and agent-initiated trading action. */
    private static final Logger audit = LoggerFactory.getLogger("audit.advisor");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ClaudeGateway claude;
    private final McpToolGateway mcp;
    private final ToolCatalog catalog;
    private final int maxIterations;
    private final Duration approvalTtl;
    private final Clock clock;

    @Autowired
    public AdvisorAgent(ClaudeGateway claude, McpToolGateway mcp, ToolCatalog catalog, AdvisorProperties props) {
        this(claude, mcp, catalog, props.maxToolIterations(), props.approvalTtl(), Clock.systemUTC());
    }

    AdvisorAgent(ClaudeGateway claude, McpToolGateway mcp, ToolCatalog catalog, int maxIterations,
                 Duration approvalTtl, Clock clock) {
        this.claude = claude;
        this.mcp = mcp;
        this.catalog = catalog;
        this.maxIterations = maxIterations;
        this.approvalTtl = approvalTtl;
        this.clock = clock;
    }

    /** Runs one user turn. {@code accountId} (optional, already validated) is the account typed in the UI. */
    public void chat(Conversation conversation, String text, String accountId, String bearerToken, AgentEventSink sink) {
        List<ContentBlockParam> content = new ArrayList<>();

        // Any tool_use still open in the history must be answered first, or the API rejects the request.
        PendingApproval pending = conversation.pending();
        if (pending != null) {
            for (PendingCall call : pending.calls()) {
                if (call.requiresApproval()) {
                    auditDecision(conversation, call, "SUPERSEDED", null);
                    content.add(toolResult(call.toolUseId(),
                            ToolCallOutcome.failed("Not executed: the user sent a new message instead of approving this action.")));
                } else {
                    content.add(toolResult(call.toolUseId(), call.autoOutcome()));
                }
            }
            conversation.setPending(null);
        }
        for (String id : conversation.danglingToolUseIds()) {
            content.add(toolResult(id, ToolCallOutcome.failed("Not executed: the previous turn ended before this call ran.")));
        }
        conversation.setDanglingToolUseIds(List.of());

        String prefix = accountId == null ? "" : "Active trade account: " + accountId + "\n\n";
        content.add(ContentBlockParam.ofText(prefix + text));
        conversation.history().add(MessageParam.ofUserOfContentBlockParams(content));

        runLoop(conversation, bearerToken, sink);
    }

    /** Applies the user's decisions to the pending tool calls and continues the turn. */
    public void resume(Conversation conversation, List<ApprovalDecision> decisions, String bearerToken, AgentEventSink sink) {
        PendingApproval pending = conversation.pending();
        if (pending == null) {
            sink.emit(AgentEventSink.ERROR, Map.of("message", "There is no action waiting for approval."));
            sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", false));
            return;
        }
        Map<String, ApprovalDecision> byId = decisions.stream()
                .collect(Collectors.toMap(ApprovalDecision::toolUseId, Function.identity(), (a, b) -> a));
        boolean expired = !clock.instant().isBefore(pending.expiresAt());

        List<ContentBlockParam> results = new ArrayList<>();
        for (PendingCall call : pending.calls()) {
            if (!call.requiresApproval()) {
                results.add(toolResult(call.toolUseId(), call.autoOutcome()));
                continue;
            }
            ApprovalDecision decision = byId.get(call.toolUseId());
            if (expired) {
                auditDecision(conversation, call, "EXPIRED", null);
                results.add(toolResult(call.toolUseId(), ToolCallOutcome.failed(
                        "Not executed: the approval request expired before the user decided.")));
            } else if (decision != null && decision.approved()) {
                auditDecision(conversation, call, "APPROVED", null);
                ToolCallOutcome outcome = mcp.callTool(call.name(), call.input(), bearerToken);
                audit.info("action conversation={} sub={} tool={} input={} error={} result={}", conversation.id(),
                        conversation.ownerSubject(), call.name(), call.input(), outcome.error(), outcome.content());
                sink.emit(AgentEventSink.TOOL_ACTIVITY, activity(call.name(), call.summary(), outcome));
                results.add(toolResult(call.toolUseId(), outcome));
            } else {
                String reason = decision == null ? null : blankToNull(decision.reason());
                auditDecision(conversation, call, "REJECTED", reason);
                results.add(toolResult(call.toolUseId(), ToolCallOutcome.failed(
                        "User rejected this action" + (reason == null ? "." : ": " + reason))));
            }
        }
        conversation.setPending(null);
        conversation.history().add(MessageParam.ofUserOfContentBlockParams(results));
        if (expired) {
            sink.emit(AgentEventSink.ERROR, Map.of("message", "The approval request had expired, so nothing was executed."));
        }
        runLoop(conversation, bearerToken, sink);
    }

    private void runLoop(Conversation conversation, String bearerToken, AgentEventSink sink) {
        ToolCatalog.Snapshot tools;
        try {
            tools = catalog.build(mcp.listTools(bearerToken));
        } catch (RuntimeException e) {
            log.warn("Could not list MCP tools: {}", e.toString());
            sink.emit(AgentEventSink.ERROR, Map.of("message", "The trading tools are unavailable right now (" + e.getMessage() + ")."));
            sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", false));
            return;
        }

        ClaudeGateway.StreamListener listener = new ClaudeGateway.StreamListener() {
            @Override
            public void onTextDelta(String text) {
                sink.emit(AgentEventSink.TEXT_DELTA, Map.of("text", text));
            }

            @Override
            public void onThinkingDelta(String thinking) {
                sink.emit(AgentEventSink.THINKING, Map.of("text", thinking));
            }
        };

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            Message response;
            try {
                response = claude.send(conversation.history(), tools.tools(), listener);
            } catch (RuntimeException e) {
                log.warn("Claude request failed: {}", e.toString());
                sink.emit(AgentEventSink.ERROR, Map.of("message", "The advisor could not be reached: " + e.getMessage()));
                sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", false));
                return;
            }
            // Append the full content (thinking, server tool blocks, tool_use) so the next request round-trips it unchanged.
            conversation.history().add(response.toParam());
            emitWebSearchActivity(response, sink);

            List<ToolUseBlock> toolUses = response.content().stream()
                    .flatMap(b -> b.toolUse().stream()).toList();
            StopReason stop = response.stopReason().orElse(StopReason.END_TURN);

            if (stop.equals(StopReason.PAUSE_TURN)) {
                continue; // a long-running server tool (web search) paused the turn; send it back to resume
            }
            if (!stop.equals(StopReason.TOOL_USE) || toolUses.isEmpty()) {
                conversation.setDanglingToolUseIds(toolUses.stream().map(ToolUseBlock::id).toList());
                if (stop.equals(StopReason.REFUSAL)) {
                    sink.emit(AgentEventSink.ERROR, Map.of("message", "The advisor declined to continue with this request."));
                } else if (stop.equals(StopReason.MAX_TOKENS) || stop.equals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED)) {
                    sink.emit(AgentEventSink.ERROR, Map.of("message", "The response was cut off (" + stop + ")."));
                }
                sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", false));
                return;
            }

            List<PendingCall> calls = new ArrayList<>();
            for (ToolUseBlock use : toolUses) {
                Map<String, Object> input = parseInput(use);
                String summary = Summaries.describe(use.name(), input);
                if (!tools.isClientTool(use.name())) {
                    calls.add(new PendingCall(use.id(), use.name(), input, summary, false,
                            ToolCallOutcome.failed("Unknown tool: " + use.name())));
                } else if (tools.requiresApproval(use.name())) {
                    calls.add(new PendingCall(use.id(), use.name(), input, summary, true, null));
                } else {
                    ToolCallOutcome outcome = mcp.callTool(use.name(), input, bearerToken);
                    sink.emit(AgentEventSink.TOOL_ACTIVITY, activity(use.name(), summary, outcome));
                    calls.add(new PendingCall(use.id(), use.name(), input, summary, false, outcome));
                }
            }

            if (calls.stream().anyMatch(PendingCall::requiresApproval)) {
                PendingApproval pending = new PendingApproval(List.copyOf(calls), clock.instant().plus(approvalTtl));
                conversation.setPending(pending);
                pending.awaitingDecision().forEach(c -> audit.info(
                        "approval_requested conversation={} sub={} tool={} input={}",
                        conversation.id(), conversation.ownerSubject(), c.name(), c.input()));
                sink.emit(AgentEventSink.APPROVAL_REQUIRED, approvalEvent(pending));
                sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", true));
                return;
            }

            conversation.history().add(MessageParam.ofUserOfContentBlockParams(
                    calls.stream().map(c -> toolResult(c.toolUseId(), c.autoOutcome())).toList()));
        }

        sink.emit(AgentEventSink.ERROR, Map.of("message", "Stopped after " + maxIterations + " steps without finishing."));
        sink.emit(AgentEventSink.DONE, Map.of("awaitingApproval", false));
    }

    private static Map<String, Object> parseInput(ToolUseBlock use) {
        try {
            Map<String, Object> input = use._input().convert(MAP);
            return input == null ? Map.of() : input;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static ContentBlockParam toolResult(String toolUseId, ToolCallOutcome outcome) {
        String content = outcome.content() == null || outcome.content().isBlank() ? "(no content)" : outcome.content();
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(toolUseId)
                .content(content)
                .isError(outcome.error())
                .build());
    }

    private static Map<String, Object> approvalEvent(PendingApproval pending) {
        List<Map<String, Object>> actions = pending.awaitingDecision().stream()
                .map(c -> {
                    Map<String, Object> action = new LinkedHashMap<>();
                    action.put("toolUseId", c.toolUseId());
                    action.put("tool", c.name());
                    action.put("summary", c.summary());
                    action.put("input", c.input());
                    return action;
                })
                .toList();
        return Map.of("expiresAt", pending.expiresAt().toString(), "actions", actions);
    }

    private static Map<String, Object> activity(String tool, String label, ToolCallOutcome outcome) {
        return Map.of("kind", "tool", "tool", tool, "label", label, "status", outcome.error() ? "error" : "ok");
    }

    private static void emitWebSearchActivity(Message response, AgentEventSink sink) {
        for (ContentBlock block : response.content()) {
            block.serverToolUse().ifPresent(use -> {
                Object query = null;
                try {
                    query = use._input().convert(MAP).get("query");
                } catch (RuntimeException ignored) {
                    // not every server tool input has a query
                }
                sink.emit(AgentEventSink.TOOL_ACTIVITY, Map.of("kind", "web_search", "tool", "web_search",
                        "label", "Searching: " + Objects.toString(query, "the web")));
            });
            block.webSearchToolResult().ifPresent(result -> {
                List<Map<String, String>> sources = result.content().resultBlocks().orElse(List.of()).stream()
                        .limit(8)
                        .map(r -> Map.of("title", r.title(), "url", r.url()))
                        .toList();
                if (!sources.isEmpty()) {
                    sink.emit(AgentEventSink.TOOL_ACTIVITY, Map.of("kind", "web_results", "tool", "web_search",
                            "label", sources.size() + " results", "sources", sources));
                }
            });
        }
    }

    private void auditDecision(Conversation conversation, PendingCall call, String decision, String reason) {
        audit.info("approval_decision conversation={} sub={} tool={} decision={} reason={} input={}", conversation.id(),
                conversation.ownerSubject(), call.name(), decision, reason, call.input());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
