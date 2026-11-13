package com.juzo.ai.ordermanager.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Values published in the OAuth 2.0 Protected Resource Metadata document (RFC 9728),
 * which MCP clients use to discover how to obtain a token for this server.
 */
@ConfigurationProperties(prefix = "mcp.oauth")
public record OAuthResourceProperties(
        String resource,
        List<String> authorizationServers,
        List<String> scopesSupported
) {}
