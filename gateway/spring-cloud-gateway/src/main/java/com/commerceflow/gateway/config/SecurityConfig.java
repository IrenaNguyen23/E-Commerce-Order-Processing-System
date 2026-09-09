package com.commerceflow.gateway.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import com.commerceflow.common.security.JwtTokenProvider;
import com.commerceflow.common.security.SecurityHeaders;
import com.commerceflow.gateway.filter.JwtAuthenticationWebFilter;

/**
 * Edge authorisation.
 *
 * <p>Stateless: no session, no CSRF token, no login page. The gateway holds the whole allow-list
 * of unauthenticated routes in one place, which is what makes it reviewable — every other route
 * requires a verified access token.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Obtaining a token, reading the public catalogue, probes and API documentation. */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            // Guest checkout. Safe to expose because it refuses any address that
            // already has an account -- see GuestSessionService.
            "/api/auth/guest",
            // Account recovery: used by people who cannot sign in, which is the whole point.
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/verify-email",
            "/api/auth/resend-verification",
            // Signed by the acquirer, not by us. See StripeWebhookController.
            "/api/payments/webhook",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/fallback/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/**",
            "/v3/api-docs",
            "/v3/api-docs/**"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         JwtTokenProvider tokenProvider,
                                                         GatewayProperties properties) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .securityContextRepository(
                        org.springframework.security.web.server.context
                                .NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        // The catalogue is public: customers browse before they sign in.
                        .pathMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/categories", "/api/categories/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/products/lookup").permitAll()

                        // Writing a review is a customer action that happens to sit under a
                        // product path. It has to be listed BEFORE the admin rules below, which
                        // are broad enough to swallow it -- and did, until this line existed.
                        .pathMatchers(HttpMethod.PUT, "/api/products/*/reviews").authenticated()

                        // Everything that changes the catalogue is back office only.
                        .pathMatchers(HttpMethod.POST, "/api/products").hasRole(SecurityHeaders.ROLE_ADMIN)
                        .pathMatchers(HttpMethod.POST, "/api/products/*/images").hasRole(SecurityHeaders.ROLE_ADMIN)
                        .pathMatchers(HttpMethod.PUT, "/api/products/**").hasRole(SecurityHeaders.ROLE_ADMIN)
                        .pathMatchers(HttpMethod.DELETE, "/api/products/**").hasRole(SecurityHeaders.ROLE_ADMIN)
                        .pathMatchers("/api/categories/**").hasRole(SecurityHeaders.ROLE_ADMIN)
                        .pathMatchers("/api/warehouses/**").hasRole(SecurityHeaders.ROLE_ADMIN)

                        // The audit trail, read only, back office only. Each service checks this
                        // again with @PreAuthorize — a request that reaches a pod directly must
                        // not be trusted — but rejecting here saves a proxied round trip and
                        // keeps the rule visible in the one place the whole API surface is listed.
                        .pathMatchers("/api/audit/**").hasRole(SecurityHeaders.ROLE_ADMIN)

                        .anyExchange().authenticated())
                .addFilterAt(new JwtAuthenticationWebFilter(tokenProvider),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new RestServerAuthenticationEntryPoint())
                        .accessDeniedHandler(new RestServerAccessDeniedHandler()))
                .build();
    }

    private CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
