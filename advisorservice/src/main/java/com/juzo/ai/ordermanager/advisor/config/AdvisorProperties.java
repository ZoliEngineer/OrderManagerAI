package com.juzo.ai.ordermanager.advisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("advisor")
public record AdvisorProperties(
        String anthropicApiKey,
        String model,
        String effort,
        long maxTokens,
        long maxWebSearchesPerTurn,
        int maxToolIterations,
        boolean refusalFallback,
        Duration approvalTtl,
        Duration conversationIdleTtl,
        String mcpUrl,
        Duration mcpRequestTimeout,
        Duration toolListCacheTtl,
        Ui ui,
        boolean webSearchDynamicFiltering) {

    public record Ui(String tenantId, String clientId, String scope) {}
}
