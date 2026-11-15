package com.juzo.ai.ordermanager.risk.config;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes every gRPC call a server span, continuing the trace the caller sent in the
 * {@code traceparent} metadata header (Order Service -> Risk Service).
 */
@Configuration
public class GrpcObservationConfig {

    @Bean
    @GrpcGlobalServerInterceptor
    ObservationGrpcServerInterceptor observationGrpcServerInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcServerInterceptor(registry);
    }
}
