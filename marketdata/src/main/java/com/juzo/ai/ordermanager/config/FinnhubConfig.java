package com.juzo.ai.ordermanager.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

@Configuration
@ConditionalOnProperty(name = "finnhub.api-key")
public class FinnhubConfig {

    @Bean
    WebClient finnhubRestClient(@Value("${finnhub.rest-base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    ReactorNettyWebSocketClient finnhubWsClient() {
        return new ReactorNettyWebSocketClient();
    }
}
