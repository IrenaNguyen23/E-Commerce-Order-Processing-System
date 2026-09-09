package com.commerceflow.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import com.commerceflow.common.security.JwtAuthenticationFilter;
import com.commerceflow.common.security.JwtProperties;
import com.commerceflow.common.security.JwtTokenProvider;
import com.commerceflow.common.security.TokenRevocationChecker;
import com.commerceflow.common.web.RestAccessDeniedHandler;
import com.commerceflow.common.web.RestAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared JWT plumbing.
 *
 * <p>{@link JwtTokenProvider} is always available (the reactive gateway needs it too); the
 * servlet filter and the REST error handlers are only contributed to servlet applications that
 * actually have Spring Security on the classpath.
 *
 * <p>The {@code SecurityFilterChain} itself is deliberately left to each service, because the
 * authorisation rules differ per bounded context.
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        return new JwtTokenProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenRevocationChecker tokenRevocationChecker() {
        return TokenRevocationChecker.alwaysActive();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityContextHolder.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletSecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                                        TokenRevocationChecker revocationChecker) {
            return new JwtAuthenticationFilter(tokenProvider, revocationChecker);
        }

        /**
         * Keeps the filter out of the plain servlet filter chain: it is wired explicitly into the
         * Spring Security chain by each service, and registering it twice would be misleading.
         */
        @Bean
        FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
                JwtAuthenticationFilter filter) {
            FilterRegistrationBean<JwtAuthenticationFilter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }

        @Bean
        @ConditionalOnMissingBean
        RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
            return new RestAuthenticationEntryPoint(objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
            return new RestAccessDeniedHandler(objectMapper);
        }
    }
}
