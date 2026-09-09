package com.commerceflow.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.commerceflow.common.idempotency.IdempotencyProperties;
import com.commerceflow.common.idempotency.IdempotencyScheduler;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.idempotency.ProcessedEventRepository;

import jakarta.persistence.EntityManagerFactory;

/**
 * Wires the consumer idempotency ledger, which turns the at-least-once delivery of the outbox
 * relay into effectively-once processing.
 *
 * <p>A service opts in by including {@code com.commerceflow.common.idempotency} in its
 * {@code @EnableJpaRepositories} and {@code @EntityScan} base packages.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManagerFactory.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class CommonIdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(ProcessedEventRepository.class)
    @ConditionalOnMissingBean
    public IdempotencyService idempotencyService(ProcessedEventRepository repository) {
        return new IdempotencyService(repository);
    }

    /** Bounded-growth housekeeping for the ledger. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "commerceflow.idempotency", name = "cleanup-enabled",
            havingValue = "true", matchIfMissing = true)
    static class IdempotencySchedulingConfiguration {

        @Bean
        @ConditionalOnBean(IdempotencyService.class)
        @ConditionalOnMissingBean
        IdempotencyScheduler idempotencyScheduler(IdempotencyService idempotencyService,
                                                  IdempotencyProperties properties) {
            return new IdempotencyScheduler(idempotencyService, properties);
        }
    }
}
