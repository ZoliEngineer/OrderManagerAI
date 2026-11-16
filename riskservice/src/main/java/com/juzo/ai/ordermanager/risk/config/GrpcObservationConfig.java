package com.juzo.ai.ordermanager.risk.config;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Times every gRPC call served here (the {@code grpc.server} metrics), by method and status. */
@Configuration
public class GrpcObservationConfig {

    @Bean
    @GrpcGlobalServerInterceptor
    ObservationGrpcServerInterceptor observationGrpcServerInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcServerInterceptor(registry);
    }
}
