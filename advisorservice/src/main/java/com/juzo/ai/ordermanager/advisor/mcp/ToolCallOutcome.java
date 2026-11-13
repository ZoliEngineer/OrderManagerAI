package com.juzo.ai.ordermanager.advisor.mcp;

/** Result of one MCP tool call, as text handed back to Claude in a tool_result block. */
public record ToolCallOutcome(String content, boolean error) {

    public static ToolCallOutcome ok(String content) {
        return new ToolCallOutcome(content, false);
    }

    public static ToolCallOutcome failed(String content) {
        return new ToolCallOutcome(content, true);
    }
}
