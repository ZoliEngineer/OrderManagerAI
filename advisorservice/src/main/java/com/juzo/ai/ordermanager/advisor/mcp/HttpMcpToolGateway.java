package com.juzo.ai.ordermanager.advisor.mcp;

import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Streamable-HTTP MCP client. The MCP service is stateless, so a short-lived client is opened per
 * operation with the caller's bearer token; the token is never shared between users.
 */
@Component
public class HttpMcpToolGateway implements McpToolGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpToolGateway.class);

    private final AdvisorProperties props;
    private final String baseUrl;
    private final String endpoint;
    private final Clock clock;

    /** Tool definitions are the same for every user and only change on deploy; cache them briefly. */
    private volatile CachedTools cachedTools;

    private record CachedTools(List<McpSchema.Tool> tools, Instant expiresAt) {}

    @Autowired
    public HttpMcpToolGateway(AdvisorProperties props) {
        this(props, Clock.systemUTC());
    }

    HttpMcpToolGateway(AdvisorProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        URI uri = URI.create(props.mcpUrl());
        this.baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        this.endpoint = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/mcp" : uri.getRawPath();
    }

    @Override
    public List<McpSchema.Tool> listTools(String bearerToken) {
        CachedTools cached = cachedTools;
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.tools();
        }
        try (McpSyncClient client = open(bearerToken)) {
            List<McpSchema.Tool> tools = List.copyOf(client.listTools().tools());
            cachedTools = new CachedTools(tools, clock.instant().plus(props.toolListCacheTtl()));
            return tools;
        }
    }

    @Override
    public ToolCallOutcome callTool(String name, Map<String, Object> arguments, String bearerToken) {
        try (McpSyncClient client = open(bearerToken)) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
            String text = result.content() == null ? "" : result.content().stream()
                    .map(c -> c instanceof McpSchema.TextContent t ? t.text() : c.toString())
                    .collect(Collectors.joining("\n"));
            return new ToolCallOutcome(text, Boolean.TRUE.equals(result.isError()));
        } catch (RuntimeException e) {
            log.warn("MCP tool call {} failed: {}", name, e.toString());
            return ToolCallOutcome.failed("Tool call failed before a result was received (" + e.getMessage()
                    + "). For place_order the order status is unknown: check the order list instead of retrying.");
        }
    }

    private McpSyncClient open(String bearerToken) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("ordermanager-advisor", "1.0.0"))
                .requestTimeout(props.mcpRequestTimeout())
                .build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }
}
