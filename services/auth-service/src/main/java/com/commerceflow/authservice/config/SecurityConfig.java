package com.commerceflow.authservice.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.commerceflow.authservice.security.RedisTokenRevocationChecker;
import com.commerceflow.authservice.service.TokenBlacklistService;
import com.commerceflow.common.security.JwtAuthenticationFilter;
import com.commerceflow.common.security.SecurityHeaders;
import com.commerceflow.common.security.TokenRevocationChecker;
import com.commerceflow.common.web.RestAccessDeniedHandler;
import com.commerceflow.common.web.RestAuthenticationEntryPoint;

/**
 * Stateless JWT security for Auth Service.
 *
 * <p>Registration, login and refresh must stay open — they are how a caller obtains a token in the
 * first place. Everything else requires a verified access token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            // Guest checkout, by definition used by someone with no account. Safe to
            // expose because it refuses any address that already has one -- see
            // GuestSessionService for why that refusal is the whole security model.
            "/api/auth/guest",
            // Account recovery is by definition used by someone who cannot authenticate.
            // Each of these is safe to expose because none of them reveals whether an address
            // is registered, and each consumes a single-use token rather than a credential.
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/verify-email",
            "/api/auth/resend-verification",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/actuator/**").hasRole(SecurityHeaders.ROLE_ADMIN)
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * BCrypt at the Spring Security default strength: a deliberate balance between resistance to
     * offline cracking and the login latency budget.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Overrides the no-op checker from {@code commerceflow-common} with the Redis deny-list. */
    @Bean
    public TokenRevocationChecker tokenRevocationChecker(TokenBlacklistService blacklistService) {
        return new RedisTokenRevocationChecker(blacklistService);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
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
