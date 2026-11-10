package com.juzo.ai.ordermanager.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource server for the MCP endpoint. Spring Security serves the OAuth 2.0 Protected Resource
 * Metadata document (RFC 9728) at {@code /.well-known/oauth-protected-resource} and advertises it in
 * the {@code WWW-Authenticate} header of 401 responses, as required by the MCP authorization spec.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String REQUIRED_SCOPE = "SCOPE_mcp.trade";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OAuthResourceProperties oauth) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/mcp", "/mcp/**").hasAuthority(REQUIRED_SCOPE)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(builder -> {
                            if (oauth.resource() != null) builder.resource(oauth.resource());
                            if (oauth.authorizationServers() != null) oauth.authorizationServers().forEach(builder::authorizationServer);
                            if (oauth.scopesSupported() != null) oauth.scopesSupported().forEach(builder::scope);
                            builder.tlsClientCertificateBoundAccessTokens(false);
                        })))
                .build();
    }
}
