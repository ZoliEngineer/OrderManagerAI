package com.juzo.ai.ordermanager.mcp.tools;

import com.juzo.ai.ordermanager.mcp.config.McpTransportConfig;
import io.micrometer.observation.Observation;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import java.util.function.Supplier;

/** The authenticated caller of an MCP tool, as captured from the HTTP request by the transport. */
record CallerContext(String bearerToken, String subject, String clientId, Observation requestObservation) {

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
                (String) transport.get(McpTransportConfig.CTX_CLIENT_ID),
                transport.get(McpTransportConfig.CTX_OBSERVATION) instanceof Observation o ? o : null);
    }

    /** Runs a downstream call inside the MCP request's trace, so its client span is a child of that request. */
    <T> T call(Supplier<T> downstream) {
        if (requestObservation == null) {
            return downstream.get();
        }
        try (Observation.Scope ignored = requestObservation.openScope()) {
            return downstream.get();
        }
    }

    void run(Runnable downstream) {
        call(() -> {
            downstream.run();
            return null;
        });
    }
}
