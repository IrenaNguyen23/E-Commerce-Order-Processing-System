package com.commerceflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import com.commerceflow.common.security.AuthenticatedUser;

import reactor.core.publisher.Mono;

/**
 * Redis backed rate limiting.
 *
 * <p>Buckets are keyed by authenticated user where possible and by client address otherwise. That
 * distinction matters: keying everything by IP would throttle a whole office behind one NAT as if
 * it were a single abusive client, while keying everything by user would leave the unauthenticated
 * login and registration endpoints — the ones actually worth brute forcing — unprotected.
 *
 * <p>Redis rather than in-memory counters, so the limit is a property of the platform and not of
 * whichever gateway replica happened to receive the request.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /** Per-user where authenticated, per-IP otherwise. */
    @Bean
    @Primary
    public KeyResolver clientKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication().getPrincipal())
                .filter(AuthenticatedUser.class::isInstance)
                .map(principal -> "user:" + ((AuthenticatedUser) principal).userId())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientAddress(exchange)));
    }

    /** Always per-IP; used on the authentication routes, which are pre-login by definition. */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + clientAddress(exchange));
    }

    private static String clientAddress(org.springframework.web.server.ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
