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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.commerceflow.common.kafka.EventPublisher;
import com.commerceflow.common.outbox.OutboxProperties;
import com.commerceflow.common.outbox.OutboxRelay;
import com.commerceflow.common.outbox.OutboxRepository;
import com.commerceflow.common.outbox.OutboxScheduler;
import com.commerceflow.common.outbox.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;

/**
 * Wires the transactional outbox (ADR-006) into any service that has both JPA and Kafka and
 * that exposes the shared {@link OutboxRepository}.
 *
 * <p>A service opts in simply by including {@code com.commerceflow.common.outbox} in its
 * {@code @EnableJpaRepositories} and {@code @EntityScan} base packages.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, KafkaTemplate.class})
@ConditionalOnProperty(prefix = "commerceflow.outbox", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class CommonOutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(OutboxRepository.class)
    @ConditionalOnMissingBean
    public OutboxService outboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        return new OutboxService(repository, objectMapper);
    }

    @Bean
    @ConditionalOnBean(OutboxRepository.class)
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxRepository repository,
                                   EventPublisher eventPublisher,
                                   ObjectMapper objectMapper,
                                   OutboxProperties properties) {
        return new OutboxRelay(repository, eventPublisher, objectMapper, properties);
    }

    /** The polling half of the relay; can be switched off in tests that drive it directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "commerceflow.outbox", name = "scheduler-enabled",
            havingValue = "true", matchIfMissing = true)
    static class OutboxSchedulingConfiguration {

        @Bean
        // Nested configurations are evaluated before the enclosing configuration's @Bean
        // methods. Check the repository, which is already registered, rather than the relay
        // that the enclosing configuration has not registered yet.
        @ConditionalOnBean(OutboxRepository.class)
        @ConditionalOnMissingBean
        OutboxScheduler outboxScheduler(OutboxRelay relay, OutboxProperties properties) {
            return new OutboxScheduler(relay, properties);
        }
    }
}
