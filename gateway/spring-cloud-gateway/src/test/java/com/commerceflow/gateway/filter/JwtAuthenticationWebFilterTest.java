package com.commerceflow.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.common.security.JwtProperties;
import com.commerceflow.common.security.JwtTokenProvider;
import com.commerceflow.common.security.SecurityHeaders;

import reactor.core.publisher.Mono;

/**
 * The gateway is the one place that turns a token into an identity, so these tests pin down both
 * halves of that job: the security context it populates, and the headers it hands downstream.
 */
class JwtAuthenticationWebFilterTest {

    private static final String SECRET = "commerceflow-unit-test-secret-key-of-at-least-32-bytes";
    private static final UUID USER_ID = UUID.randomUUID();

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationWebFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("commerceflow");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        tokenProvider = new JwtTokenProvider(properties);
        filter = new JwtAuthenticationWebFilter(tokenProvider);
    }

    @Test
    @DisplayName("a valid token becomes an authenticated principal and X-User-* headers")
    void validTokenIsPropagatedDownstream() {
        String token = tokenProvider.generateAccessToken(USER_ID, "ada@commerceflow.io",
                Set.of(SecurityHeaders.ROLE_CUSTOMER));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        AtomicReference<AuthenticatedUser> principal = new AtomicReference<>();

        WebFilterChain chain = forwarded -> {
            downstream.set(forwarded);
            return ReactiveSecurityContextHolder.getContext()
                    .map(SecurityContext::getAuthentication)
                    .doOnNext(auth -> principal.set((AuthenticatedUser) auth.getPrincipal()))
                    .then();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(5));

        HttpHeaders headers = downstream.get().getRequest().getHeaders();
        assertThat(headers.getFirst(SecurityHeaders.USER_ID)).isEqualTo(USER_ID.toString());
        assertThat(headers.getFirst(SecurityHeaders.USER_EMAIL)).isEqualTo("ada@commerceflow.io");
        assertThat(headers.getFirst(SecurityHeaders.USER_ROLES))
                .isEqualTo(SecurityHeaders.ROLE_CUSTOMER);

        assertThat(principal.get()).isNotNull();
        assertThat(principal.get().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("client supplied X-User-* headers are stripped so identity cannot be spoofed")
    void spoofedIdentityHeadersAreStripped() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(SecurityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(SecurityHeaders.USER_EMAIL, "mallory@evil.io")
                        .header(SecurityHeaders.USER_ROLES, "ADMIN"));

        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        WebFilterChain chain = forwarded -> {
            downstream.set(forwarded);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(5));

        HttpHeaders headers = downstream.get().getRequest().getHeaders();
        assertThat(headers.getFirst(SecurityHeaders.USER_ID)).isNull();
        assertThat(headers.getFirst(SecurityHeaders.USER_EMAIL)).isNull();
        assertThat(headers.getFirst(SecurityHeaders.USER_ROLES)).isNull();
    }

    @Test
    @DisplayName("a forged token is ignored: the request continues unauthenticated")
    void forgedTokenIsIgnored() {
        JwtProperties foreign = new JwtProperties();
        foreign.setSecret("a-completely-different-secret-key-with-32-bytes-plus");
        foreign.setIssuer("commerceflow");
        String forged = new JwtTokenProvider(foreign)
                .generateAccessToken(UUID.randomUUID(), "mallory@evil.io", Set.of("ADMIN"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged));

        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        AtomicReference<Boolean> authenticated = new AtomicReference<>(false);
        WebFilterChain chain = forwarded -> {
            downstream.set(forwarded);
            return ReactiveSecurityContextHolder.getContext()
                    .doOnNext(context -> authenticated.set(true))
                    .then();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(5));

        assertThat(authenticated.get()).isFalse();
        assertThat(downstream.get().getRequest().getHeaders()
                .getFirst(SecurityHeaders.USER_ROLES)).isNull();
    }

    @Test
    @DisplayName("a request without a token passes through untouched")
    void anonymousRequestPassesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products"));

        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        WebFilterChain chain = forwarded -> {
            downstream.set(forwarded);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(5));

        assertThat(downstream.get()).isNotNull();
        assertThat(downstream.get().getRequest().getHeaders()
                .getFirst(SecurityHeaders.USER_ID)).isNull();
    }
}
