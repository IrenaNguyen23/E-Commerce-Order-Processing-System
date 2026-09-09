package com.commerceflow.common.context;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Holder for the request/saga correlation id.
 *
 * <p>Backed by SLF4J's {@link MDC} so the value automatically appears in every structured log
 * line without having to be threaded through method signatures.
 */
public final class CorrelationContext {

    /** HTTP header carrying the correlation id in and out of the platform. */
    public static final String HEADER = "X-Correlation-Id";

    /** Kafka header carrying the correlation id between saga participants. */
    public static final String KAFKA_HEADER = "X-Correlation-Id";

    /** MDC key; referenced by {@code logback-commerceflow.xml}. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
        throw new AssertionError("No instances");
    }

    /** @return the current correlation id, or {@code null} when none is bound. */
    public static String get() {
        return MDC.get(MDC_KEY);
    }

    /** @return the current correlation id, generating and binding a new one when absent. */
    public static String getOrCreate() {
        String current = get();
        if (current == null || current.isBlank()) {
            current = UUID.randomUUID().toString();
            set(current);
        }
        return current;
    }

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            clear();
        } else {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * Runs {@code action} with {@code correlationId} bound, restoring the previous value
     * afterwards. Used by Kafka listeners, which run on container threads.
     */
    public static void runWith(String correlationId, Runnable action) {
        String previous = get();
        try {
            set(correlationId);
            action.run();
        } finally {
            set(previous);
            if (previous == null) {
                clear();
            }
        }
    }
}
