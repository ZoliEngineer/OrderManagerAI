package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the MCP service's tools into Claude tool definitions and decides which ones need the user's approval.
 * A tool runs without approval only when the MCP server marks it {@code readOnlyHint=true}; every other tool
 * (today place_order and cancel_order, and any mutating tool added later) waits for the user.
 */
@Component
public class ToolCatalog {

    private final ToolUnion webSearch;

    public ToolCatalog(AdvisorProperties props) {
        long maxUses = props.maxWebSearchesPerTurn();
        // The dynamic-filtering variant needs Opus/Sonnet 4.6+; other models (e.g. Haiku) get the basic one.
        this.webSearch = props.webSearchDynamicFiltering()
                ? ToolUnion.ofWebSearchTool20260209(WebSearchTool20260209.builder().maxUses(maxUses).build())
                : ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().maxUses(maxUses).build());
    }

    public record Snapshot(List<ToolUnion> tools, Set<String> clientToolNames, Set<String> approvalRequired) {

        public boolean isClientTool(String name) {
            return clientToolNames.contains(name);
        }

        public boolean requiresApproval(String name) {
            return approvalRequired.contains(name);
        }
    }

    public Snapshot build(List<McpSchema.Tool> mcpTools) {
        // Sorted so the tool list is byte-stable between requests (tools are part of the cached prompt prefix).
        List<McpSchema.Tool> sorted = mcpTools.stream().sorted(Comparator.comparing(McpSchema.Tool::name)).toList();

        List<ToolUnion> tools = new ArrayList<>();
        tools.add(webSearch);
        sorted.forEach(t -> tools.add(ToolUnion.ofTool(toClaudeTool(t))));

        Set<String> names = sorted.stream().map(McpSchema.Tool::name).collect(Collectors.toUnmodifiableSet());
        Set<String> approval = sorted.stream().filter(ToolCatalog::requiresApproval)
                .map(McpSchema.Tool::name).collect(Collectors.toUnmodifiableSet());
        return new Snapshot(List.copyOf(tools), names, approval);
    }

    static boolean requiresApproval(McpSchema.Tool tool) {
        return tool.annotations() == null || !Boolean.TRUE.equals(tool.annotations().readOnlyHint());
    }

    static Tool toClaudeTool(McpSchema.Tool mcp) {
        Map<String, Object> schema = mcp.inputSchema() == null ? Map.of() : mcp.inputSchema();
        Tool.InputSchema.Builder input = Tool.InputSchema.builder();

        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            properties.forEach((k, v) -> props.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
        }
        input.properties(props.build());

        if (schema.get("required") instanceof List<?> required && !required.isEmpty()) {
            input.required(required.stream().map(String::valueOf).toList());
        }
        schema.forEach((k, v) -> {
            if (!Set.of("type", "properties", "required", "$schema").contains(k)) {
                input.putAdditionalProperty(k, JsonValue.from(v));
            }
        });

        String description = mcp.description() != null ? mcp.description() : (mcp.title() != null ? mcp.title() : mcp.name());
        return Tool.builder()
                .name(mcp.name())
                .description(description)
                .inputSchema(input.build())
                .build();
    }
}
