package com.juzo.ai.ordermanager.account.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecurityConfigTest {

    private final JwtSecurityConfig config = new JwtSecurityConfig();
    private final OAuth2TokenValidator<Jwt> validator = config.clientIdValidator(List.of("my-client"));

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
    }

    @Test
    void entraStyleTokenWithAudIsAccepted() {
        assertThat(validator.validate(jwt(Map.of("sub", "u", "aud", List.of("my-client")))).hasErrors()).isFalse();
    }

    @Test
    void cognitoAccessTokenWithClientIdIsAccepted() {
        assertThat(validator.validate(jwt(Map.of("sub", "u", "client_id", "my-client", "token_use", "access"))).hasErrors()).isFalse();
    }

    @Test
    void cognitoIdTokenIsRejected() {
        assertThat(validator.validate(jwt(Map.of("sub", "u", "aud", List.of("my-client"), "token_use", "id"))).hasErrors()).isTrue();
    }

    @Test
    void tokenForAnotherClientIsRejected() {
        assertThat(validator.validate(jwt(Map.of("sub", "u", "client_id", "other"))).hasErrors()).isTrue();
    }

    @Test
    void noConfiguredClientsRejectsEverything() {
        var closed = config.clientIdValidator(List.of());
        assertThat(closed.validate(jwt(Map.of("sub", "u", "client_id", "my-client"))).hasErrors()).isTrue();
    }

    @Test
    void cognitoScopePrefixIsStripped() {
        var auth = config.jwtAuthenticationConverter().convert(jwt(Map.of("sub", "u", "scope", "openid ordermanager/mcp.trade")));
        assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains("SCOPE_mcp.trade", "SCOPE_openid");
    }
}
