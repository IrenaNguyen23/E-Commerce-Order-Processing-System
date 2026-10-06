package com.commerceflow.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.commerceflow.common.kafka.EventPublisher;
import com.commerceflow.common.outbox.OutboxRelay;
import com.commerceflow.common.outbox.OutboxRepository;
import com.commerceflow.common.outbox.OutboxScheduler;
import com.commerceflow.common.outbox.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;

class CommonOutboxAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonOutboxAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
            .withPropertyValues("commerceflow.outbox.poll-interval-ms=3600000");

    @Test
    void createsSchedulerForAutoConfiguredRelay() {
        contextRunner.withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxService.class);
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).hasSingleBean(OutboxScheduler.class);
                });
    }

    @Test
    void schedulerCanBeDisabledWithoutDisablingTheRelay() {
        contextRunner.withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .withPropertyValues("commerceflow.outbox.scheduler-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).doesNotHaveBean(OutboxScheduler.class);
                });
    }

    @Test
    void doesNotCreateOutboxWithoutARepository() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(OutboxService.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
            assertThat(context).doesNotHaveBean(OutboxScheduler.class);
        });
    }
}
