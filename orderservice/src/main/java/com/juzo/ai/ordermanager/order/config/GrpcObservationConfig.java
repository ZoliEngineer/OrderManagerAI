package com.juzo.ai.ordermanager.order.config;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes every outgoing gRPC call a client span and injects the {@code traceparent} metadata header,
 * so the Risk Service's server span joins the order's trace.
 */
@Configuration
public class GrpcObservationConfig {

    @Bean
    @GrpcGlobalClientInterceptor
    ObservationGrpcClientInterceptor observationGrpcClientInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcClientInterceptor(registry);
    }
}
