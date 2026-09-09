package com.commerceflow.common.autoconfigure;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.kafka.CorrelationRecordInterceptor;
import com.commerceflow.common.kafka.EventPublisher;
import com.commerceflow.common.kafka.KafkaTypeMappings;

import lombok.extern.slf4j.Slf4j;

/**
 * Platform-wide Kafka behaviour: logical type aliases on the wire, correlation propagation and
 * a bounded retry policy that parks poison records on a dead-letter topic.
 *
 * <p>Serializer classes and connection settings come from {@code commerceflow-kafka.yml}, which
 * every service imports through {@code spring.config.import}.
 */
@Slf4j
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class CommonKafkaAutoConfiguration {

    /**
     * Writes the logical event name into the {@code __TypeId__} header instead of the Java class
     * name, so the wire contract survives refactoring and stays consumable by non-JVM clients.
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer commerceFlowProducerFactoryCustomizer() {
        return producerFactory -> producerFactory.updateConfigs(Map.of(
                JsonSerializer.TYPE_MAPPINGS, KafkaTypeMappings.mappingProperty(),
                JsonSerializer.ADD_TYPE_INFO_HEADERS, Boolean.TRUE));
    }

    /** Resolves those aliases back to the shared event classes on the consumer side. */
    @Bean
    public DefaultKafkaConsumerFactoryCustomizer commerceFlowConsumerFactoryCustomizer() {
        return consumerFactory -> consumerFactory.updateConfigs(Map.of(
                JsonDeserializer.TYPE_MAPPINGS, KafkaTypeMappings.mappingProperty(),
                JsonDeserializer.TRUSTED_PACKAGES, "com.commerceflow.common.event",
                JsonDeserializer.USE_TYPE_INFO_HEADERS, Boolean.TRUE));
    }

    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public RecordInterceptor<Object, Object> correlationRecordInterceptor() {
        return new CorrelationRecordInterceptor();
    }

    /**
     * The single seam through which this platform writes to Kafka.
     *
     * <p>Injected as {@code KafkaTemplate<?, ?>} — exactly the type Spring Boot declares — so
     * nothing here depends on the leniency of generic autowiring.
     */
    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher eventPublisher(KafkaTemplate<?, ?> kafkaTemplate) {
        return new EventPublisher(kafkaTemplate);
    }

    /**
     * Retries transient failures with an exponential back-off, then routes the record to
     * {@code <topic>.DLT}. Domain rule violations and malformed payloads are never retried:
     * replaying them cannot change the outcome, so they go straight to the dead-letter topic.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    log.error("Routing record from {}-{} offset {} to the dead-letter topic",
                            record.topic(), record.partition(), record.offset(), exception);
                    return new TopicPartition(record.topic() + KafkaTopics.DEAD_LETTER_SUFFIX, -1);
                });

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(60_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(
                BusinessException.class,
                IllegalArgumentException.class,
                NullPointerException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }
}
