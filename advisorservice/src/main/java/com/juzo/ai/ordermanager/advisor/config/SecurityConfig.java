package com.juzo.ai.ordermanager.advisor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The static chat page and its config are public; the chat API requires an Entra ID token carrying the
 * {@code mcp.trade} scope, because that same token is forwarded to the MCP service to place orders.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String REQUIRED_SCOPE = "SCOPE_mcp.trade";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico",
                                "/config.json", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/**").hasAuthority(REQUIRED_SCOPE)
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }
}
