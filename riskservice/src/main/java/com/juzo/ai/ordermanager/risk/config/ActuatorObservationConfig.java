package com.juzo.ai.ordermanager.risk.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** No spans for Prometheus scrapes and health probes, which would otherwise flood the trace list. */
@Configuration(proxyBeanMethods = false)
class ActuatorObservationConfig {

    @Bean
    ObservationPredicate ignoreActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext ctx
                && ctx.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
