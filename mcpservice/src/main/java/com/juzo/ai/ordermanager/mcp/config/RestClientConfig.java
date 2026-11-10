package com.juzo.ai.ordermanager.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient orderServiceRestClient(DownstreamProperties props) {
        return RestClient.builder()
                .baseUrl(props.orderServiceUrl())
                .requestFactory(requestFactory(props.connectTimeout(), props.orderReadTimeout()))
                .build();
    }

    @Bean
    RestClient marketDataRestClient(DownstreamProperties props) {
        return RestClient.builder()
                .baseUrl(props.marketDataUrl())
                .requestFactory(requestFactory(props.connectTimeout(), props.marketDataReadTimeout()))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
