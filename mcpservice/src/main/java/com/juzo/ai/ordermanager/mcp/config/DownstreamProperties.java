package com.juzo.ai.ordermanager.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Base URLs and timeouts of the services the MCP tools delegate to. */
@ConfigurationProperties(prefix = "mcp.downstream")
public record DownstreamProperties(
        String orderServiceUrl,
        String marketDataUrl,
        Duration connectTimeout,
        Duration orderReadTimeout,
        Duration marketDataReadTimeout
) {
    public DownstreamProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (orderReadTimeout == null) orderReadTimeout = Duration.ofSeconds(3);
        if (marketDataReadTimeout == null) marketDataReadTimeout = Duration.ofSeconds(2);
    }
}
