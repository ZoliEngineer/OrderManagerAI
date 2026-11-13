package com.juzo.ai.ordermanager.advisor.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/** Access to the trading tools of the MCP service, always on behalf of the signed-in user. */
public interface McpToolGateway {

    List<McpSchema.Tool> listTools(String bearerToken);

    /** Never throws for tool or transport failures; those come back as {@link ToolCallOutcome#error()}. */
    ToolCallOutcome callTool(String name, Map<String, Object> arguments, String bearerToken);
}
