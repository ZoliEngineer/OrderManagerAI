package com.juzo.ai.ordermanager.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identity-provider-neutral JWT client check. Entra ID puts the client id in {@code aud}; Cognito access
 * tokens have no {@code aud} and carry it in {@code client_id}. Either is accepted if listed in
 * {@code app.security.allowed-client-ids}, and ID tokens ({@code token_use=id}) are rejected.
 * Spring Boot adds this validator to the auto-configured decoder, which also serves the WebSocket auth frame.
 */
@Configuration
public class JwtSecurityConfig {

    @Bean
    OAuth2TokenValidator<Jwt> clientIdValidator(@Value("${app.security.allowed-client-ids:}") List<String> allowedClientIds) {
        List<String> allowed = allowedClientIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        OAuth2Error error = new OAuth2Error("invalid_token", "Token was not issued to an allowed client", null);
        return jwt -> {
            String tokenUse = jwt.getClaimAsString("token_use");
            boolean accessToken = tokenUse == null || "access".equals(tokenUse);
            boolean clientAllowed = (jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(allowed::contains))
                    || allowed.contains(jwt.getClaimAsString("client_id"));
            return accessToken && clientAllowed
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
