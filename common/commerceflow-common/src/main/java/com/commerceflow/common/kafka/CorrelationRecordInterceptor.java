package com.commerceflow.common.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

import com.commerceflow.common.context.CorrelationContext;

/**
 * Binds the correlation id carried on a Kafka record to the logging MDC for the duration of the
 * listener invocation, so a saga can be followed across services in the structured logs.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(CorrelationContext.KAFKA_HEADER);
        if (header != null && header.value() != null) {
            CorrelationContext.set(new String(header.value(), StandardCharsets.UTF_8));
        } else {
            CorrelationContext.set(null);
            CorrelationContext.getOrCreate();
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        CorrelationContext.clear();
    }
}
