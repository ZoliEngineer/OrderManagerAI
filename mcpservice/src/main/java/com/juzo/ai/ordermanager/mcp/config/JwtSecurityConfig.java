package com.juzo.ai.ordermanager.mcp.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Identity-provider-neutral JWT handling. The issuer comes from {@code OIDC_ISSUER_URI}; this class adds
 * the two things that differ between OIDC providers:
 * <ul>
 *   <li>the client check: Entra ID puts the client id in {@code aud}, Cognito access tokens have no
 *       {@code aud} and carry it in {@code client_id}. Either is accepted if it is in
 *       {@code app.security.allowed-client-ids}, and ID tokens ({@code token_use=id}) are rejected;</li>
 *   <li>scope names: Cognito prefixes custom scopes with the resource server id
 *       ({@code ordermanager/mcp.trade}); the prefix is stripped so authorities are always
 *       {@code SCOPE_mcp.trade}.</li>
 * </ul>
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

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> scopes.convert(jwt).stream()
                .<GrantedAuthority>map(a -> {
                    String s = a.getAuthority();
                    int slash = s.lastIndexOf('/');
                    return slash < 0 ? a : new SimpleGrantedAuthority("SCOPE_" + s.substring(slash + 1));
                })
                .toList());
        return converter;
    }
}
