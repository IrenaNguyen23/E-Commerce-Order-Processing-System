package com.commerceflow.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.commerceflow.common.audit.AuditController;
import com.commerceflow.common.audit.AuditProperties;
import com.commerceflow.common.audit.AuditQueryService;
import com.commerceflow.common.audit.AuditRepository;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.audit.AuditSweeper;

import jakarta.persistence.EntityManagerFactory;

/**
 * Wires the operator audit trail.
 *
 * <p>A service opts in by including {@code com.commerceflow.common.audit} in its
 * {@code @EnableJpaRepositories} and {@code @EntityScan} base packages, and by creating the
 * {@code admin_audit_log} table in its own migrations — the same arrangement as the outbox and the
 * idempotency ledger, and for the same reason: each service owns its own tables.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManagerFactory.class)
@EnableConfigurationProperties(AuditProperties.class)
public class CommonAuditAutoConfiguration {

    @Bean
    @ConditionalOnBean(AuditRepository.class)
    @ConditionalOnMissingBean
    public AuditService auditService(AuditRepository repository) {
        return new AuditService(repository);
    }

    /**
     * The read side.
     *
     * <p>Only in a servlet application, and only where there is something to read. The gateway
     * pulls this library in too, and it has no database — contributing a controller there would
     * publish an endpoint that could only ever fail.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class AuditReadConfiguration {

        @Bean
        @ConditionalOnBean(AuditRepository.class)
        @ConditionalOnMissingBean
        AuditQueryService auditQueryService(
                AuditRepository repository,
                @Value("${spring.application.name:unknown}") String serviceName) {

            // Stamped onto every row so a merged view across five services can say where each
            // one came from. Read from configuration rather than passed in by each service,
            // which would be five identical strings to keep in step with five yaml files.
            return new AuditQueryService(repository, serviceName);
        }

        @Bean
        @ConditionalOnBean(AuditQueryService.class)
        @ConditionalOnMissingBean
        AuditController auditController(AuditQueryService queryService) {
            return new AuditController(queryService);
        }
    }

    /**
     * Retention housekeeping.
     *
     * <p>Switchable off, and that switch is not a convenience: a deployment under a legal hold
     * must not delete anything, and stopping the job is a guarantee where a very large retention
     * is a hope.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "commerceflow.audit", name = "cleanup-enabled",
            havingValue = "true", matchIfMissing = true)
    static class AuditSchedulingConfiguration {

        @Bean
        @ConditionalOnBean(AuditRepository.class)
        @ConditionalOnMissingBean
        AuditSweeper auditSweeper(AuditRepository repository, AuditProperties properties) {
            return new AuditSweeper(repository, properties);
        }
    }
}
