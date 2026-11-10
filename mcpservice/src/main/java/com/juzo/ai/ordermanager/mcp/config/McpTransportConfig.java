package com.juzo.ai.ordermanager.mcp.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Replaces the auto-configured Streamable HTTP transport so that every MCP request carries the
 * caller's validated JWT in its {@link McpTransportContext}. Tools read the token from there
 * rather than from the thread-local SecurityContext, so token forwarding does not depend on
 * which thread the MCP server runs the tool callback on.
 */
@Configuration
public class McpTransportConfig {

    public static final String CTX_BEARER_TOKEN = "bearerToken";
    public static final String CTX_SUBJECT = "subject";
    public static final String CTX_CLIENT_ID = "clientId";

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper, McpServerStreamableHttpProperties props) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(request -> extractCallerContext(request.headers().firstHeader(HttpHeaders.AUTHORIZATION)))
                .build();
    }

    static McpTransportContext extractCallerContext(String authorizationHeader) {
        Map<String, Object> values = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            values.put(CTX_BEARER_TOKEN, jwtAuth.getToken().getTokenValue());
            values.put(CTX_SUBJECT, jwtAuth.getToken().getSubject());
            String clientId = jwtAuth.getToken().getClaimAsString("azp");
            if (clientId == null) clientId = jwtAuth.getToken().getClaimAsString("appid");
            if (clientId != null) values.put(CTX_CLIENT_ID, clientId);
        } else if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            values.put(CTX_BEARER_TOKEN, authorizationHeader.substring(7));
        }
        return McpTransportContext.create(values);
    }
}
