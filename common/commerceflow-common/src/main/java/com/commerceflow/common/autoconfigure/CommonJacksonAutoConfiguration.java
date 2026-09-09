package com.commerceflow.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Pins the JSON dialect used on the wire and on the Kafka topics.
 *
 * <p>Consistency matters more than any individual setting here: producer and consumer live in
 * different services, so both must agree that timestamps are ISO-8601 strings and that unknown
 * fields are tolerated (which is what makes additive event evolution safe).
 */
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class CommonJacksonAutoConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer commerceFlowJacksonCustomizer() {
        return builder -> builder
                .modulesToInstall(JavaTimeModule.class)
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
