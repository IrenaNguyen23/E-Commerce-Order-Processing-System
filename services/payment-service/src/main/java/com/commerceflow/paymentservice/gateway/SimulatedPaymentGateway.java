package com.commerceflow.paymentservice.gateway;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.commerceflow.paymentservice.config.PaymentProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in acquirer, so the platform runs end to end without third-party credentials.
 *
 * <p>Two decline rules, both configurable:
 * <ul>
 *   <li><b>Amount threshold</b> — deterministic, so the compensation branch of the saga can be
 *       triggered on demand in a demo or an integration test without injecting a failure.</li>
 *   <li><b>Failure rate</b> — random, for soak testing the retry and compensation paths. Zero by
 *       default, because a non-deterministic default would make every other test flaky.</li>
 * </ul>
 *
 * <p>Replaced in production by an adapter for the real provider; nothing else changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "commerceflow.payment", name = "gateway",
        havingValue = "SIMULATED", matchIfMissing = true)
public class SimulatedPaymentGateway implements PaymentGateway {

    static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    static final String REASON_CARD_DECLINED = "CARD_DECLINED";
    static final String REASON_INVALID_AMOUNT = "INVALID_AMOUNT";
    static final String REASON_UNKNOWN_REFERENCE = "UNKNOWN_GATEWAY_REFERENCE";

    private final PaymentProperties properties;

    @Override
    public ChargeResult charge(UUID orderId, BigDecimal amount, String currency) {
        simulateLatency();

        if (amount == null || amount.signum() <= 0) {
            return ChargeResult.declined(REASON_INVALID_AMOUNT);
        }
        if (amount.compareTo(properties.getDeclineThreshold()) >= 0) {
            log.info("Simulated decline for order {}: {} {} is at or above the {} threshold",
                    orderId, amount, currency, properties.getDeclineThreshold());
            return ChargeResult.declined(REASON_INSUFFICIENT_FUNDS);
        }
        if (properties.getFailureRate() > 0
                && ThreadLocalRandom.current().nextDouble() < properties.getFailureRate()) {
            log.info("Simulated random decline for order {}", orderId);
            return ChargeResult.declined(REASON_CARD_DECLINED);
        }

        String transactionId = "SIM-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        log.info("Simulated charge approved for order {}: {} {} ({})", orderId, amount, currency,
                transactionId);
        return ChargeResult.approved(transactionId);
    }

    /**
     * Always succeeds.
     *
     * <p>A simulated acquirer that randomly refused refunds would make the cancellation flow
     * undemonstrable, and the interesting failure — an acquirer that will not give money back —
     * is one an operator handles rather than the saga.
     */
    @Override
    public RefundResult refund(UUID orderId, String gatewayReference, BigDecimal amount,
                               String currency) {
        simulateLatency();

        String reference = "SIMREF-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 18).toUpperCase(java.util.Locale.ROOT);
        log.info("Simulated refund of {} {} for order {} ({})", amount, currency, orderId,
                reference);
        return RefundResult.succeeded(reference);
    }

    /**
     * Nothing to reconcile: this acquirer decides on the spot.
     *
     * <p>Answering {@code approved} here would be a lie the saga acts on, so a reference the
     * simulator never issued is reported as declined — which is the truth about a charge it has
     * no record of.
     */
    @Override
    public ChargeResult reconcile(String gatewayReference) {
        log.warn("Nothing to reconcile for {}: the simulated acquirer settles synchronously",
                gatewayReference);
        return ChargeResult.declined(REASON_UNKNOWN_REFERENCE);
    }

    private void simulateLatency() {
        long delay = properties.getProcessingDelayMs();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
