package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real SDK against a scripted SSE stream to check the request shape and stream accumulation. */
class AnthropicClaudeGatewayTest {

    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void streamsTextAndSendsExpectedRequest() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse()));
        server.start();
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url("/").toString())
                .maxRetries(0).build();
        AdvisorProperties props = new AdvisorProperties(null, "claude-opus-5-5", "high", 64000, 5, 20, true,
                Duration.ofMinutes(10), Duration.ofHours(2), "http://x/mcp", Duration.ofSeconds(1), Duration.ofMinutes(1), null, true);
        var catalog = new ToolCatalog(props).build(List.of());
        var gateway = new AnthropicClaudeGateway(client, props, new SystemPrompt());

        List<String> deltas = new ArrayList<>();
        Message message = gateway.send(List.of(MessageParam.ofUser("Hi")), catalog.tools(), new ClaudeGateway.StreamListener() {
            @Override
            public void onTextDelta(String text) {
                deltas.add(text);
            }

            @Override
            public void onThinkingDelta(String thinking) {
            }
        });

        assertThat(deltas).containsExactly("Hel", "lo");
        assertThat(message.stopReason()).contains(StopReason.END_TURN);
        assertThat(message.content().getFirst().asText().text()).isEqualTo("Hello");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        String body = request.getBody().readUtf8();
        assertThat(request.getHeader("anthropic-beta")).contains(AnthropicClaudeGateway.FALLBACK_BETA);
        assertThat(body)
                .contains("\"model\":\"claude-opus-5-5\"")
                .contains("\"stream\":true")
                .contains("\"thinking\":{\"type\":\"adaptive\",\"display\":\"summarized\"}")
                .contains("\"effort\":\"high\"")
                .contains("\"fallbacks\":\"default\"")
                .contains("\"type\":\"web_search_20260209\"")
                .contains("\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}");
    }

    /**
     * Captured from the live API with dynamic-filtering web search: the continuation stream starts with a
     * programmatic {@code web_search} server_tool_use whose whole input is in content_block_start and which never
     * receives an input_json_delta. The SDK accumulator alone fails this with "Missing input JSON for index 0".
     */
    @Test
    void acceptsToolBlocksThatCarryTheirInputInTheStartEventOnly() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(programmaticWebSearchSse()));
        server.start();
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url("/").toString())
                .maxRetries(0).build();
        AdvisorProperties props = new AdvisorProperties(null, "claude-sonnet-5-5", "low", 64000, 5, 20, true,
                Duration.ofMinutes(10), Duration.ofHours(2), "http://x/mcp", Duration.ofSeconds(1), Duration.ofMinutes(1), null, true);
        var catalog = new ToolCatalog(props).build(List.of());
        var gateway = new AnthropicClaudeGateway(client, props, new SystemPrompt());

        Message message = gateway.send(List.of(MessageParam.ofUser("Hi")), catalog.tools(), new ClaudeGateway.StreamListener() {
            @Override
            public void onTextDelta(String text) {
            }

            @Override
            public void onThinkingDelta(String thinking) {
            }
        });

        var webSearch = message.content().getFirst().asServerToolUse();
        assertThat(webSearch.name().asString()).isEqualTo("web_search");
        assertThat(webSearch._input().toString()).contains("Apple stock news today");
        // A block that did stream its input through deltas is left untouched.
        var toolUse = message.content().get(1).asToolUse();
        assertThat(toolUse.name()).isEqualTo("get_market_prices");
        assertThat(toolUse._input().toString()).contains("AAPL");
        assertThat(message.stopReason()).contains(StopReason.TOOL_USE);
    }

    private static String programmaticWebSearchSse() {
        return String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\",\"input\":{\"query\":\"Apple stock news today\"}}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_market_prices\",\"input\":{}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"tickers\\\":[\\\"AAPL\\\"]}\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":1}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "", "");
    }

    private static String sse() {
        return String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "", "");
    }
}
