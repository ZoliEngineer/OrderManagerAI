package com.juzo.ai.ordermanager.advisor.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    /**
     * The key is read from Spring properties (so the shared {@code .env} file works) and falls back to the
     * SDK's own environment lookup ({@code ANTHROPIC_API_KEY}, {@code ant auth login} profile, ...).
     */
    @Bean(destroyMethod = "close")
    AnthropicClient anthropicClient(AdvisorProperties props) {
        if (props.anthropicApiKey() != null && !props.anthropicApiKey().isBlank()) {
            return AnthropicOkHttpClient.builder().apiKey(props.anthropicApiKey()).build();
        }
        return AnthropicOkHttpClient.fromEnv();
    }
}
