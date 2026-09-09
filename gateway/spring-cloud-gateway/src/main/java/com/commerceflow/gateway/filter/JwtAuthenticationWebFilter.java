package com.commerceflow.gateway.filter;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.commerceflow.common.exception.UnauthorizedException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.common.security.JwtTokenProvider;
import com.commerceflow.common.security.SecurityHeaders;
import com.commerceflow.common.security.TokenType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Verifies the access token once, at the edge, and passes the caller identity downstream.
 *
 * <p>Two responsibilities, deliberately in one filter because they must not diverge:
 * <ol>
 *   <li><b>Authenticate</b> — populate the reactive security context so the route-level rules in
 *       {@code SecurityConfig} can allow or reject the exchange.</li>
 *   <li><b>Propagate</b> — replace the {@code X-User-*} headers with values derived from the
 *       verified token.</li>
 * </ol>
 *
 * <p>The inbound {@code X-User-*} headers are stripped <em>unconditionally</em>, before anything
 * else and whether or not a token is present. Without that, a client could simply send
 * {@code X-User-Roles: ADMIN} and a downstream service that trusted the header would believe it.
 * (The services verify the JWT themselves as well, so this is defence in depth rather than the
 * only line of defence.)
 *
 * <p>An invalid token is not rejected here: the exchange continues unauthenticated and the
 * authorisation rules decide, which keeps every 401 and 403 on a single, consistent path.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationWebFilter implements WebFilter, Ordered {

    private final JwtTokenProvider tokenProvider;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest sanitised = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(SecurityHeaders.USER_ID);
                    headers.remove(SecurityHeaders.USER_EMAIL);
                    headers.remove(SecurityHeaders.USER_ROLES);
                })
                .build();

        String token = JwtTokenProvider.resolveBearerToken(
                sanitised.getHeaders().getFirst(SecurityHeaders.AUTHORIZATION));

        if (token == null) {
            return chain.filter(exchange.mutate().request(sanitised).build());
        }

        AuthenticatedUser user;
        try {
            user = tokenProvider.authenticate(token, TokenType.ACCESS);
        } catch (UnauthorizedException ex) {
            log.debug("Rejected token at the edge: {}", ex.getMessage());
            return chain.filter(exchange.mutate().request(sanitised).build());
        }

        ServerHttpRequest enriched = sanitised.mutate()
                .headers(headers -> {
                    headers.set(SecurityHeaders.USER_ID, user.userId().toString());
                    if (user.email() != null) {
                        headers.set(SecurityHeaders.USER_EMAIL, user.email());
                    }
                    headers.set(SecurityHeaders.USER_ROLES, String.join(",", user.roles()));
                })
                .build();

        return chain.filter(exchange.mutate().request(enriched).build())
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(toAuthentication(user)))));
    }

    private static Authentication toAuthentication(AuthenticatedUser user) {
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
