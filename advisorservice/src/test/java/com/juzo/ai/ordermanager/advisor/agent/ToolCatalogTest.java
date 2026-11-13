package com.juzo.ai.ordermanager.advisor.agent;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import com.juzo.ai.ordermanager.advisor.config.AdvisorProperties;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    private final ToolCatalog catalog = new ToolCatalog(new AdvisorProperties(null, "m", "high", 1000, 3, 5, false,
            Duration.ofMinutes(1), Duration.ofHours(1), "http://x/mcp", Duration.ofSeconds(1), Duration.ofMinutes(1), null, true));

    @Test
    void approvalFollowsReadOnlyHint() {
        var snapshot = catalog.build(List.of(
                tool("place_order", new McpSchema.ToolAnnotations("p", false, false, false, true, false)),
                tool("get_market_prices", new McpSchema.ToolAnnotations("g", true, false, true, true, false)),
                tool("unannotated", null)));

        assertThat(snapshot.requiresApproval("place_order")).isTrue();
        assertThat(snapshot.requiresApproval("unannotated")).isTrue();
        assertThat(snapshot.requiresApproval("get_market_prices")).isFalse();
        assertThat(snapshot.isClientTool("web_search")).isFalse();
    }

    @Test
    void webSearchFirstThenToolsSortedByName() {
        var snapshot = catalog.build(List.of(tool("place_order", null), tool("cancel_order", null),
                tool("get_market_prices", null)));

        assertThat(snapshot.tools()).hasSize(4);
        assertThat(snapshot.tools().getFirst().isWebSearchTool20260209()).isTrue();
        assertThat(snapshot.tools().subList(1, 4)).extracting(t -> t.asTool().name())
                .containsExactly("cancel_order", "get_market_prices", "place_order");
    }

    @Test
    void basicWebSearchWhenDynamicFilteringIsOff() {
        var basic = new ToolCatalog(new AdvisorProperties(null, "claude-haiku-5-5", "low", 1000, 3, 5, false,
                Duration.ofMinutes(1), Duration.ofHours(1), "http://x/mcp", Duration.ofSeconds(1), Duration.ofMinutes(1),
                null, false));

        assertThat(basic.build(List.of()).tools()).singleElement()
                .satisfies(t -> assertThat(t.isWebSearchTool20250305()).isTrue());
    }

    @Test
    void inputSchemaIsCarriedOver() throws Exception {
        Tool tool = ToolCatalog.toClaudeTool(McpSchema.Tool.builder()
                .name("cancel_order")
                .description("Cancels an order")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("orderId", Map.of("type", "string", "format", "uuid")),
                        "required", List.of("orderId"),
                        "additionalProperties", false))
                .build());

        String json = ObjectMappers.jsonMapper().writeValueAsString(tool);
        assertThat(json).contains("\"orderId\":{").contains("\"format\":\"uuid\"")
                .contains("\"required\":[\"orderId\"]").contains("\"additionalProperties\":false")
                .contains("\"description\":\"Cancels an order\"");
    }

    private static McpSchema.Tool tool(String name, McpSchema.ToolAnnotations annotations) {
        return McpSchema.Tool.builder().name(name).description(name)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .annotations(annotations).build();
    }
}
