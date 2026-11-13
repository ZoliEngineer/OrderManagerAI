package com.juzo.ai.ordermanager.advisor.mcp;

import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Talks to a scripted Streamable-HTTP MCP endpoint and checks the user's token is forwarded on every request. */
class HttpMcpToolGatewayTest {

    private final MockWebServer server = new MockWebServer();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpMcpToolGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                if (!"POST".equals(request.getMethod())) return new MockResponse().setResponseCode(405);
                String body = request.getBody().readUtf8();
                String id = body.replaceAll("(?s).*\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+).*", "$1");
                if (body.contains("\"method\":\"initialize\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\","
                            + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
                }
                if (body.contains("\"method\":\"notifications/")) {
                    return new MockResponse().setResponseCode(202);
                }
                if (body.contains("\"method\":\"tools/list\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"place_order\","
                            + "\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"},"
                            + "\"annotations\":{\"readOnlyHint\":false}}]}}");
                }
                if (body.contains("\"method\":\"tools/call\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\","
                            + "\"text\":\"{\\\"accepted\\\":true}\"}],\"isError\":false}}");
                }
                return new MockResponse().setResponseCode(400);
            }
        });
        server.start();
        AdvisorProperties props = new AdvisorProperties(null, null, null, 0, 0, 0, false, null, null,
                server.url("/mcp").toString(), Duration.ofSeconds(5), Duration.ofMinutes(5), null, true);
        gateway = new HttpMcpToolGateway(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void listsToolsWithUserToken() {
        var tools = gateway.listTools("tok-1");

        assertThat(tools).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("place_order");
            assertThat(t.annotations().readOnlyHint()).isFalse();
        });
        assertThat(requests).isNotEmpty()
                .allSatisfy(r -> assertThat(r.getHeader("Authorization")).isEqualTo("Bearer tok-1"));
    }

    @Test
    void callToolForwardsTokenAndReturnsText() {
        ToolCallOutcome outcome = gateway.callTool("place_order", Map.of("ticker", "AAPL"), "tok-2");

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).isEqualTo("{\"accepted\":true}");
        assertThat(requests).allSatisfy(r -> assertThat(r.getHeader("Authorization")).isEqualTo("Bearer tok-2"));
    }

    @Test
    void transportFailureBecomesErrorOutcome() throws Exception {
        server.shutdown();

        ToolCallOutcome outcome = gateway.callTool("place_order", Map.of(), "tok");

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("status is unknown");
    }
}
