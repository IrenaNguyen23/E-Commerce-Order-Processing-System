package com.commerceflow.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.commerceflow.common.web.CorrelationIdFilter;
import com.commerceflow.common.web.DataAccessExceptionHandler;
import com.commerceflow.common.web.GlobalExceptionHandler;
import com.commerceflow.common.web.SecurityExceptionHandler;

/**
 * Cross-cutting servlet concerns every REST service shares: correlation id propagation and a
 * single, uniform error envelope.
 *
 * <p>Not activated in the reactive gateway, which has its own WebFlux equivalents.
 */
@AutoConfiguration
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** Registered only when Spring Security is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AccessDeniedException.class)
    static class SecurityAdviceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SecurityExceptionHandler securityExceptionHandler() {
            return new SecurityExceptionHandler();
        }
    }

    /** Registered only when the Spring DAO exception hierarchy is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataIntegrityViolationException.class)
    static class DataAccessAdviceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DataAccessExceptionHandler dataAccessExceptionHandler() {
            return new DataAccessExceptionHandler();
        }
    }
}
