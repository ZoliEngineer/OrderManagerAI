package com.juzo.ai.ordermanager.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AAD_TENANT_ID=test-tenant",
        "AAD_CLIENT_ID=test-client",
        "mcp.trading.max-notional-per-order=",
        "mcp.trading.max-quantity-per-order=",
        "server.shutdown=immediate"
})
class McpServerIntegrationTest {

    private static final String TRADE_TOKEN = "trade-token";
    private static final String NO_SCOPE_TOKEN = "no-scope-token";

    static final MockWebServer orderServer = start();
    static final MockWebServer marketServer = start();

    @LocalServerPort
    int port;

    private static MockWebServer start() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return server;
    }

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("mcp.downstream.order-service-url", () -> orderServer.url("/").toString());
        registry.add("mcp.downstream.market-data-url", () -> marketServer.url("/").toString());
    }

    @AfterAll
    static void shutdown() throws IOException {
        orderServer.shutdown();
        marketServer.shutdown();
    }

    /** Fixed decoder (not a resettable mock) — the MCP client may send its session-close request late. */
    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case TRADE_TOKEN -> jwt(TRADE_TOKEN, "access_as_user mcp.trade");
                case NO_SCOPE_TOKEN -> jwt(NO_SCOPE_TOKEN, "access_as_user");
                default -> throw new BadJwtException("invalid token");
            };
        }
    }

    private static Jwt jwt(String value, String scopes) {
        return Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject("advisor-sub")
                .claim("scp", scopes)
                .claim("azp", "agent-client")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private McpSyncClient client() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + TRADE_TOKEN))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        return client;
    }

    @Test
    void listsExactlyTheThreeTradingTools() {
        try (McpSyncClient client = client()) {
            var tools = client.listTools().tools();

            assertThat(tools).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("get_market_prices", "place_order", "cancel_order");
            McpSchema.Tool cancel = tools.stream().filter(t -> t.name().equals("cancel_order")).findFirst().orElseThrow();
            assertThat(cancel.annotations().destructiveHint()).isTrue();
            McpSchema.Tool prices = tools.stream().filter(t -> t.name().equals("get_market_prices")).findFirst().orElseThrow();
            assertThat(prices.annotations().readOnlyHint()).isTrue();
        }
    }

    @Test
    void placeOrder_forwardsCallerTokenToOrderService() throws Exception {
        orderServer.enqueue(new MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                .setBody("{\"orderId\":\"22222222-2222-2222-2222-222222222222\",\"status\":\"PENDING\",\"message\":\"Order accepted\"}"));

        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("place_order", Map.of(
                    "accountId", "11111111-1111-1111-1111-111111111111",
                    "ticker", "AAPL",
                    "side", "BUY",
                    "type", "LIMIT",
                    "quantity", 5,
                    "limitPrice", 100.5)));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                    .contains("\"accepted\":true", "PENDING");
        }
        RecordedRequest forwarded = orderServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer " + TRADE_TOKEN);
    }

    @Test
    void placeOrder_invalidInput_returnsToolError() {
        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("place_order", Map.of(
                    "accountId", "11111111-1111-1111-1111-111111111111",
                    "ticker", "AAPL",
                    "side", "BUY",
                    "type", "LIMIT",
                    "quantity", 5)));

            assertThat(result.isError()).isTrue();
        }
    }

    @Test
    void missingToken_returns401WithResourceMetadata() throws Exception {
        HttpResponse<String> response = post(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
                .startsWith("Bearer")
                .contains("resource_metadata=\"http://localhost:" + port + "/.well-known/oauth-protected-resource\"");
    }

    @Test
    void tokenWithoutTradeScope_returns403() throws Exception {
        assertThat(post(NO_SCOPE_TOKEN).statusCode()).isEqualTo(403);
    }

    @Test
    void protectedResourceMetadata_isPublic() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/oauth-protected-resource")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("authorization_servers", "login.microsoftonline.com/test-tenant",
                "api://test-client/mcp.trade");
    }

    private HttpResponse<String> post(String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
