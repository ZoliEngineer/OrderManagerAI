package com.juzo.ai.ordermanager.mcp.tools;

import com.juzo.ai.ordermanager.mcp.config.McpTransportConfig;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

/** The authenticated caller of an MCP tool, as captured from the HTTP request by the transport. */
record CallerContext(String bearerToken, String subject, String clientId) {

    static CallerContext from(McpSyncRequestContext context) {
        McpTransportContext transport = context == null ? null : context.transportContext();
        if (transport == null) {
            throw new IllegalStateException("No caller credentials available for this MCP request");
        }
        Object token = transport.get(McpTransportConfig.CTX_BEARER_TOKEN);
        if (!(token instanceof String bearer) || bearer.isBlank()) {
            throw new IllegalStateException("No caller credentials available for this MCP request");
        }
        return new CallerContext(bearer,
                (String) transport.get(McpTransportConfig.CTX_SUBJECT),
                (String) transport.get(McpTransportConfig.CTX_CLIENT_ID));
    }
}
