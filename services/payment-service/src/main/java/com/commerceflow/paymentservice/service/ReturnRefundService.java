package com.commerceflow.paymentservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.event.RefundReturnCommand;
import com.commerceflow.common.event.ReturnRefundedEvent;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.paymentservice.entity.Payment;
import com.commerceflow.paymentservice.entity.PaymentRefund;
import com.commerceflow.paymentservice.gateway.PaymentGateway;
import com.commerceflow.paymentservice.repository.PaymentRefundRepository;
import com.commerceflow.paymentservice.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refunding goods a customer sent back.
 *
 * <h2>Deliberately not part of {@link PaymentService}</h2>
 *
 * <p>That class serves the saga: charge, and reverse the charge if the order never completed. Its
 * refund is all-or-nothing and answers a repeat with "already refunded", which is exactly right
 * for compensation and exactly wrong here.
 *
 * <p>A return is partial by nature and repeatable: two of three items come back this week, the
 * third next month. Each one is a separate movement of money against the same payment. Putting
 * both behaviours in one method would mean one of them was wrong for its caller.
 *
 * <h2>What makes it safe to send twice</h2>
 *
 * <p>The return id is the idempotency key, and it is a unique constraint. A redelivered command
 * finds the existing row and answers from it without touching the acquirer. That is anchored to
 * something the business recognises rather than to a message id, which would only deduplicate one
 * delivery of one message and not a relay republishing after a restart.
 *
 * <h2>Never more than is left</h2>
 *
 * <p>A refund is capped at the payment less everything already successfully refunded. Asking for
 * more is a bug upstream, and it is refused here rather than discovered on a bank statement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnRefundService {

    private static final String AGGREGATE_TYPE = "PAYMENT";

    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final PaymentGateway paymentGateway;
    private final OutboxService outboxService;
    private final AuditService auditService;

    /**
     * Sends money back for a return, and answers on {@code return.refunded} either way.
     *
     * <p>Never throws for a business reason. A return whose refund cannot be made needs a person,
     * not a retry — the goods are already back and the customer is already owed — so the outcome
     * travels in the reply and the return stops saying "in progress".
     */
    @Transactional
    public void refund(RefundReturnCommand command) {
        String key = command.getReturnId().toString();

        Optional<PaymentRefund> existing = refunds.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            PaymentRefund refund = existing.get();
            log.info("Return {} was already refunded; answering from the record", key);
            reply(command, refund.getAmount(), refund.getExternalReference(),
                    refund.isSucceeded(), refund.getFailureReason());
            return;
        }

        Optional<Payment> found = payments.findByOrderId(command.getOrderId());
        if (found.isEmpty()) {
            // Nothing was ever charged, so there is nothing to give back. Reported as a failure
            // rather than a quiet success: a return against an unpaid order is not normal, and
            // somebody should look at why it exists.
            log.error("No payment for order {}; return {} cannot be refunded",
                    command.getOrderId(), key);
            reply(command, BigDecimal.ZERO, null, false, "No payment was ever taken for this order");
            return;
        }

        Payment payment = found.get();

        BigDecimal alreadyRefunded = refunds.totalRefunded(payment.getId());
        BigDecimal remaining = payment.getAmount().subtract(alreadyRefunded);

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("Payment {} is fully refunded ({} of {}); return {} has nothing left",
                    payment.getId(), alreadyRefunded, payment.getAmount(), key);
            reply(command, BigDecimal.ZERO, null, false,
                    "This payment has already been refunded in full");
            return;
        }

        // Capped rather than refused. The order side computes the amount from its own line
        // totals, and a penny of disagreement between the two must not leave a customer waiting
        // on a support ticket. Sending back everything that is left is the answer they want.
        BigDecimal amount = command.getAmount().min(remaining);
        if (amount.compareTo(command.getAmount()) != 0) {
            log.warn("Return {} asked for {} but only {} is left on payment {}; refunding {}",
                    key, command.getAmount(), remaining, payment.getId(), amount);
        }

        PaymentGateway.RefundResult result = paymentGateway.refund(
                command.getOrderId(), payment.getGatewayReference(), amount, payment.getCurrency());

        // Recorded whether or not it worked. A failed attempt is a debt somebody has to settle,
        // and a row nobody wrote is a debt nobody can find.
        refunds.save(PaymentRefund.builder()
                .id(UUID.randomUUID())
                .paymentId(payment.getId())
                .orderId(command.getOrderId())
                .amount(amount)
                .currency(payment.getCurrency())
                .reason(command.getReason())
                .externalReference(result.success() ? result.refundReference() : null)
                .succeeded(result.success())
                .failureReason(result.success() ? null : result.failureReason())
                .idempotencyKey(key)
                .createdAt(Instant.now())
                .build());

        if (result.success()) {
            // The payment row is only marked REFUNDED when nothing is left. A partially returned
            // order is not a refunded order, and saying it is would hide what is still owed.
            if (alreadyRefunded.add(amount).compareTo(payment.getAmount()) >= 0) {
                payment.markRefunded(result.refundReference());
                payments.save(payment);
            }

            auditService.recordSystem("RETURN_REFUNDED", "PAYMENT", payment.getId(),
                    "Refunded " + amount + " " + payment.getCurrency() + " for return " + key);

            log.info("Refunded {} {} for return {} (payment {}, {})", amount,
                    payment.getCurrency(), key, payment.getId(), result.refundReference());
        } else {
            log.error("REFUND FAILED for return {} (payment {}): {}. The customer is owed {} {} "
                            + "and nobody has sent it.",
                    key, payment.getId(), result.failureReason(), amount, payment.getCurrency());
        }

        reply(command, amount, result.success() ? result.refundReference() : null,
                result.success(), result.success() ? null : result.failureReason());
    }

    private void reply(RefundReturnCommand command, BigDecimal amount, String reference,
                       boolean success, String failureReason) {

        ReturnRefundedEvent event = ReturnRefundedEvent.builder()
                .returnId(command.getReturnId())
                .orderId(command.getOrderId())
                .amount(amount)
                .currency(command.getCurrency())
                .refundReference(reference)
                .success(success)
                .failureReason(failureReason)
                .build();

        event.replyTo(command);
        outboxService.append(AGGREGATE_TYPE, command.getOrderId().toString(), event);
    }
}
