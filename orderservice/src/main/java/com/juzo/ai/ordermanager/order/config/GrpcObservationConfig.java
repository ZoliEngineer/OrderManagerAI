package com.juzo.ai.ordermanager.order.config;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Times every outgoing gRPC call (the {@code grpc.client} metrics), so Risk latency and failures show up in Prometheus. */
@Configuration
public class GrpcObservationConfig {

    @Bean
    @GrpcGlobalClientInterceptor
    ObservationGrpcClientInterceptor observationGrpcClientInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcClientInterceptor(registry);
    }
}
