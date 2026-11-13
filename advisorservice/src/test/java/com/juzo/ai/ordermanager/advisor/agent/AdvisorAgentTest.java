package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import com.juzo.ai.ordermanager.advisor.mcp.McpToolGateway;
import com.juzo.ai.ordermanager.advisor.mcp.ToolCallOutcome;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorAgentTest {

    private static final String TOKEN = "user-token";
    private static final String ACCOUNT = "11111111-1111-1111-1111-111111111111";

    private final FakeClaude claude = new FakeClaude();
    private final FakeMcp mcp = new FakeMcp();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final List<Event> events = new ArrayList<>();
    private final AgentEventSink sink = (type, data) -> events.add(new Event(type, data));
    private AdvisorAgent agent;
    private Conversation conversation;

    record Event(String type, Object data) {}

    @BeforeEach
    void setUp() {
        AdvisorProperties props = new AdvisorProperties(null, "claude-opus-5-5", "high", 64000, 5, 4, true,
                Duration.ofMinutes(10), Duration.ofHours(2), "http://localhost:8084/mcp", Duration.ofSeconds(5),
                Duration.ofMinutes(5), null, true);
        agent = new AdvisorAgent(claude, mcp, new ToolCatalog(props), 4, Duration.ofMinutes(10), clock);
        conversation = new Conversation(UUID.randomUUID(), "advisor-1", clock.instant());
    }

    @Test
    void plainAnswer_streamsTextAndFinishes() {
        claude.respond(message("end_turn", text("Hello, how can I help?")));

        agent.chat(conversation, "Hi", null, TOKEN, sink);

        assertThat(types()).containsExactly("done");
        assertThat(conversation.history()).hasSize(2);
        assertThat(mcp.calls).isEmpty();
    }

    @Test
    void accountIdIsPrefixedToUserMessage() {
        claude.respond(message("end_turn", text("ok")));

        agent.chat(conversation, "Buy something", ACCOUNT, TOKEN, sink);

        assertThat(json(claude.requests.getFirst().getFirst())).contains("Active trade account: " + ACCOUNT);
    }

    @Test
    void readOnlyTool_runsWithoutApproval() {
        claude.respond(message("tool_use", toolUse("t1", "get_market_prices", "{\"tickers\":[\"AAPL\"]}")));
        claude.respond(message("end_turn", text("AAPL is at 180.")));

        agent.chat(conversation, "Price of AAPL?", null, TOKEN, sink);

        assertThat(mcp.calls).extracting(FakeMcp.Call::name).containsExactly("get_market_prices");
        assertThat(mcp.calls.getFirst().token()).isEqualTo(TOKEN);
        assertThat(types()).containsExactly("tool_activity", "done");
        assertThat(conversation.pending()).isNull();
        ToolResultBlockParam result = toolResults(claude.requests.get(1).getLast()).getFirst();
        assertThat(result.toolUseId()).isEqualTo("t1");
        assertThat(result.isError()).contains(false);
    }

    @Test
    void placeOrder_pausesForApprovalAndIsNotExecuted() {
        claude.respond(message("tool_use", toolUse("t1", "place_order", placeOrderInput())));

        agent.chat(conversation, "Buy 10 AAPL", ACCOUNT, TOKEN, sink);

        assertThat(mcp.calls).isEmpty();
        assertThat(types()).containsExactly("approval_required", "done");
        assertThat(events.getLast().data()).isEqualTo(Map.of("awaitingApproval", true));
        @SuppressWarnings("unchecked")
        var actions = (List<Map<String, Object>>) ((Map<String, Object>) events.getFirst().data()).get("actions");
        assertThat(actions).singleElement().satisfies(a -> {
            assertThat(a.get("toolUseId")).isEqualTo("t1");
            assertThat(a.get("summary")).isEqualTo("BUY 10 AAPL LIMIT @ 180.5 on account " + ACCOUNT);
        });
        assertThat(conversation.pending()).isNotNull();
        assertThat(claude.requests).hasSize(1);
    }

    @Test
    void approve_executesOrderAndContinues() {
        claude.respond(message("tool_use", toolUse("t1", "place_order", placeOrderInput())));
        agent.chat(conversation, "Buy 10 AAPL", ACCOUNT, TOKEN, sink);
        events.clear();

        claude.respond(message("end_turn", text("Order placed.")));
        agent.resume(conversation, List.of(new ApprovalDecision("t1", true, null)), "fresh-token", sink);

        assertThat(mcp.calls).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("place_order");
            assertThat(c.token()).isEqualTo("fresh-token");
            assertThat(c.args()).containsEntry("ticker", "AAPL");
        });
        ToolResultBlockParam result = toolResults(claude.requests.get(1).getLast()).getFirst();
        assertThat(result.isError()).contains(false);
        assertThat(conversation.pending()).isNull();
        assertThat(types()).containsExactly("tool_activity", "done");
    }

    @Test
    void reject_returnsErrorResultWithReasonAndDoesNotExecute() {
        claude.respond(message("tool_use", toolUse("t1", "place_order", placeOrderInput())));
        agent.chat(conversation, "Buy 10 AAPL", ACCOUNT, TOKEN, sink);

        claude.respond(message("end_turn", text("Understood.")));
        agent.resume(conversation, List.of(new ApprovalDecision("t1", false, "too expensive")), TOKEN, sink);

        assertThat(mcp.calls).isEmpty();
        ToolResultBlockParam result = toolResults(claude.requests.get(1).getLast()).getFirst();
        assertThat(result.isError()).contains(true);
        assertThat(json(claude.requests.get(1).getLast())).contains("User rejected this action: too expensive");
    }

    @Test
    void mixedParallelCalls_allResultsGoBackInOneMessage() {
        claude.respond(message("tool_use",
                toolUse("t1", "get_market_prices", "{}"),
                toolUse("t2", "place_order", placeOrderInput())));
        agent.chat(conversation, "Check and buy", ACCOUNT, TOKEN, sink);

        assertThat(mcp.calls).extracting(FakeMcp.Call::name).containsExactly("get_market_prices");

        claude.respond(message("end_turn", text("Done.")));
        agent.resume(conversation, List.of(new ApprovalDecision("t2", true, null)), TOKEN, sink);

        List<ToolResultBlockParam> results = toolResults(claude.requests.get(1).getLast());
        assertThat(results).extracting(ToolResultBlockParam::toolUseId).containsExactly("t1", "t2");
        assertThat(mcp.calls).extracting(FakeMcp.Call::name).containsExactly("get_market_prices", "place_order");
    }

    @Test
    void expiredApproval_isTreatedAsRejected() {
        claude.respond(message("tool_use", toolUse("t1", "place_order", placeOrderInput())));
        agent.chat(conversation, "Buy 10 AAPL", ACCOUNT, TOKEN, sink);

        clock.advance(Duration.ofMinutes(11));
        claude.respond(message("end_turn", text("It expired.")));
        agent.resume(conversation, List.of(new ApprovalDecision("t1", true, null)), TOKEN, sink);

        assertThat(mcp.calls).isEmpty();
        assertThat(toolResults(claude.requests.get(1).getLast()).getFirst().isError()).contains(true);
    }

    @Test
    void newMessageWhilePending_rejectsPendingActionFirst() {
        claude.respond(message("tool_use", toolUse("t1", "place_order", placeOrderInput())));
        agent.chat(conversation, "Buy 10 AAPL", ACCOUNT, TOKEN, sink);

        claude.respond(message("end_turn", text("Sure, what instead?")));
        agent.chat(conversation, "Actually, wait", ACCOUNT, TOKEN, sink);

        assertThat(mcp.calls).isEmpty();
        MessageParam last = claude.requests.get(1).getLast();
        assertThat(toolResults(last)).singleElement().satisfies(r -> assertThat(r.isError()).contains(true));
        assertThat(json(last)).contains("Actually, wait");
        assertThat(conversation.pending()).isNull();
    }

    @Test
    void pauseTurn_isResent() {
        claude.respond(message("pause_turn", text("Searching...")));
        claude.respond(message("end_turn", text("Found it.")));

        agent.chat(conversation, "News?", null, TOKEN, sink);

        assertThat(claude.requests).hasSize(2);
        assertThat(types()).containsExactly("done");
    }

    @Test
    void iterationCapStopsTheLoop() {
        for (int i = 0; i < 4; i++) {
            claude.respond(message("tool_use", toolUse("t" + i, "get_market_prices", "{}")));
        }

        agent.chat(conversation, "Loop", null, TOKEN, sink);

        assertThat(claude.requests).hasSize(4);
        assertThat(types()).endsWith("error", "done");
    }

    @Test
    void resumeWithoutPending_reportsError() {
        agent.resume(conversation, List.of(new ApprovalDecision("x", true, null)), TOKEN, sink);

        assertThat(types()).containsExactly("error", "done");
        assertThat(claude.requests).isEmpty();
    }

    // ---- helpers ----

    private List<String> types() {
        return events.stream().map(Event::type).toList();
    }

    private static String placeOrderInput() {
        return "{\"accountId\":\"" + ACCOUNT + "\",\"ticker\":\"AAPL\",\"side\":\"BUY\",\"type\":\"LIMIT\","
                + "\"quantity\":10,\"limitPrice\":180.5}";
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\",\"citations\":null}";
    }

    private static String toolUse(String id, String name, String input) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + input + "}";
    }

    private static Message message(String stopReason, String... blocks) {
        String json = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1,"output_tokens":1}}""".formatted(String.join(",", blocks), stopReason);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ToolResultBlockParam> toolResults(MessageParam message) {
        return message.asUser().content().asContentBlockParams().stream()
                .flatMap(b -> b.toolResult().stream()).toList();
    }

    private static String json(MessageParam message) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class FakeClaude implements ClaudeGateway {
        final Deque<Message> responses = new ArrayDeque<>();
        final List<List<MessageParam>> requests = new ArrayList<>();

        void respond(Message message) {
            responses.add(message);
        }

        @Override
        public Message send(List<MessageParam> history, List<ToolUnion> tools, StreamListener listener) {
            requests.add(List.copyOf(history));
            Message next = responses.poll();
            if (next == null) throw new IllegalStateException("no scripted response");
            return next;
        }
    }

    static final class FakeMcp implements McpToolGateway {
        record Call(String name, Map<String, Object> args, String token) {}

        final List<Call> calls = new ArrayList<>();

        @Override
        public List<McpSchema.Tool> listTools(String bearerToken) {
            return List.of(
                    tool("get_market_prices", true),
                    tool("place_order", false),
                    tool("cancel_order", false));
        }

        @Override
        public ToolCallOutcome callTool(String name, Map<String, Object> arguments, String bearerToken) {
            calls.add(new Call(name, arguments, bearerToken));
            return ToolCallOutcome.ok("{\"ok\":true}");
        }

        private static McpSchema.Tool tool(String name, boolean readOnly) {
            return McpSchema.Tool.builder()
                    .name(name)
                    .description(name)
                    .inputSchema(Map.of("type", "object", "properties", Map.of()))
                    .annotations(new McpSchema.ToolAnnotations(name, readOnly, !readOnly, false, true, false))
                    .build();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
