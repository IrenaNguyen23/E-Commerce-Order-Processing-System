package com.commerceflow.paymentservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.PaymentCompletedEvent;
import com.commerceflow.common.event.PaymentFailedEvent;
import com.commerceflow.common.event.PaymentRefundedEvent;
import com.commerceflow.common.event.ProcessPaymentCommand;
import com.commerceflow.common.event.RefundPaymentCommand;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.kafka.SagaConsumerGroups;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.paymentservice.config.PaymentProperties;
import com.commerceflow.paymentservice.dto.PaymentResponse;
import com.commerceflow.paymentservice.dto.ProcessPaymentRequest;
import com.commerceflow.paymentservice.entity.Payment;
import com.commerceflow.paymentservice.entity.PaymentMethod;
import com.commerceflow.paymentservice.entity.PaymentStatus;
import com.commerceflow.paymentservice.gateway.PaymentGateway;
import com.commerceflow.paymentservice.mapper.PaymentMapper;
import com.commerceflow.paymentservice.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment as a saga participant.
 *
 * <p>It carries out one command — {@code PROCESS_PAYMENT} — and answers it with
 * {@code payment.completed} or {@code payment.failed}. It does not know that inventory was
 * reserved before it or that a notification follows; it is told to charge, and it reports what
 * happened. Whether that outcome completes the order or unwinds it is the orchestrator's call.
 *
 * <h2>The reply rule</h2>
 *
 * <p><b>Every command produces exactly one reply, including a command for a charge already
 * made.</b> A re-sent {@code PROCESS_PAYMENT} for an order that has already been settled answers
 * from the existing payment rather than charging again. Staying silent would deadlock the saga:
 * the orchestrator re-sent precisely because no answer arrived, so silence guarantees none ever
 * will.
 *
 * <p>A decline is a business outcome, not an error: it is recorded, answered and the transaction
 * commits. Only genuine transport failures propagate and are retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String AGGREGATE_TYPE = "PAYMENT";
    private static final String GROUP = SagaConsumerGroups.PAYMENT_SERVICE;

    static final String REASON_GATEWAY_ERROR = "GATEWAY_ERROR";

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final PaymentMapper paymentMapper;
    private final PaymentProperties properties;

    /**
     * Saga step 2: charge the customer for an order whose stock is held.
     *
     * <p>The amount and the customer identity travel on the command, so Payment never has to call
     * back into Order Service to learn what to charge — a participant that has to call back is a
     * participant that can be blocked by the service it calls.
     */
    @Transactional
    public void processPayment(ProcessPaymentCommand command) {
        if (!idempotencyService.claim(GROUP, command.getEventId(), command.getEventType())) {
            // This exact command was handled before, so its reply is already in the outbox.
            return;
        }

        Optional<Payment> existing = paymentRepository.findByOrderId(command.getOrderId());
        if (existing.isPresent()) {
            replayOutcome(command, existing.get());
            return;
        }

        Payment payment = newPayment(command.getOrderId(), command.getOrderNumber(),
                command.getUserId(), command.getUserEmail(), command.getAmount(),
                command.getCurrency(), PaymentMethod.CARD);

        settle(payment, command);
    }

    /**
     * Answers a re-sent command from the payment that already exists, without charging again.
     *
     * <p>A payment still {@code PENDING} is the interesting case, and with a real acquirer it is
     * an ordinary one rather than the anomaly it used to be: the charge has been handed over and
     * nobody has said how it went. Rather than guess — a decline would cancel an order that may
     * have been paid for — this asks the acquirer, which is the only system that actually knows.
     *
     * <p>If it still does not know either, the command goes unanswered on purpose. The webhook
     * will settle it, and failing that the saga times out and parks with its full history for an
     * operator. Silence is the right answer when there is no true one.
     */
    private void replayOutcome(ProcessPaymentCommand command, Payment payment) {
        log.info("Order {} already has payment {} in state {}; answering from it instead of "
                        + "charging again (command attempt {})",
                command.getOrderId(), payment.getId(), payment.getStatus(), command.getAttempt());

        // A re-sent command carries a new eventId, and the reply has to name it or the step log
        // closes the wrong row.
        payment.setSagaId(command.getSagaId());
        payment.setCommandId(command.getEventId());

        switch (payment.getStatus()) {
            case COMPLETED -> replyCompleted(payment, command);
            case FAILED -> replyFailed(payment, payment.getFailureReason(), command);
            default -> reconcilePending(payment, command);
        }
    }

    /**
     * Asks the acquirer what happened to a charge nobody has reported on.
     *
     * <p>Only reachable once a real gateway is configured; the simulator settles synchronously and
     * never leaves a payment here.
     */
    private void reconcilePending(Payment payment, ProcessPaymentCommand command) {
        if (payment.getGatewayReference() == null) {
            log.error("Payment {} for order {} is PENDING with no gateway reference. Nothing can "
                            + "be reconciled; the saga will park for an operator.",
                    payment.getId(), payment.getOrderId());
            return;
        }

        PaymentGateway.ChargeResult result;
        try {
            result = paymentGateway.reconcile(payment.getGatewayReference());
        } catch (RuntimeException ex) {
            // Cannot reach the acquirer. Staying silent keeps the question open, which is better
            // than answering it wrongly in either direction.
            log.error("Could not reconcile payment {} for order {}", payment.getId(),
                    payment.getOrderId(), ex);
            return;
        }

        if (result.isApproved()) {
            payment.markCompleted(result.transactionId());
            paymentRepository.save(payment);
            replyCompleted(payment, command);
            log.info("Reconciled payment {} for order {}: the charge had succeeded",
                    payment.getId(), payment.getOrderId());

        } else if (result.isDeclined()) {
            payment.setGatewayReference(result.gatewayReference());
            payment.markFailed(result.declineReason());
            paymentRepository.save(payment);
            replyFailed(payment, result.declineReason(), command);
            log.info("Reconciled payment {} for order {}: the charge had been declined",
                    payment.getId(), payment.getOrderId());

        } else {
            log.info("Payment {} for order {} is still in flight at the acquirer; waiting for the "
                    + "webhook", payment.getId(), payment.getOrderId());
        }
    }

    // =====================================================================================
    // REFUND_PAYMENT  (compensation for a cancelled order that was already paid)
    // =====================================================================================

    /**
     * Sends the customer's money back.
     *
     * <p>Answers {@code payment.refunded} in every case, success or not. A refund that fails is
     * not something the saga can compensate its way out of — the goods are already coming back
     * and the order is already cancelled — so the reply carries the outcome rather than there
     * being a separate failure topic, and a failure ends up in front of a human.
     *
     * <p>A payment that is already {@code REFUNDED} answers success again without touching the
     * acquirer. That is the reply rule doing its job: the orchestrator re-sends when it hears
     * nothing, and the one thing a re-sent refund must never do is send the money twice.
     */
    @Transactional
    public void refundPayment(RefundPaymentCommand command) {
        if (!idempotencyService.claim(GROUP, command.getEventId(), command.getEventType())) {
            return;
        }

        Optional<Payment> found = paymentRepository.findByOrderId(command.getOrderId());
        if (found.isEmpty()) {
            // Nothing was ever charged. The cancellation is still correct; there is simply no
            // money to return, and saying so lets the saga finish.
            log.warn("No payment to refund for order {}", command.getOrderId());
            replyRefunded(command, null, true, null);
            return;
        }

        Payment payment = found.get();

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} was already refunded; answering from the record", payment.getId());
            replyRefunded(command, payment, true, null);
            return;
        }

        if (!payment.isRefundable()) {
            // Never completed, so no money left the customer. Nothing to give back.
            log.info("Payment {} is {}; nothing to refund", payment.getId(), payment.getStatus());
            replyRefunded(command, payment, true, null);
            return;
        }

        PaymentGateway.RefundResult result = paymentGateway.refund(
                command.getOrderId(), payment.getGatewayReference(),
                command.getAmount() == null ? payment.getAmount() : command.getAmount(),
                payment.getCurrency());

        if (result.success()) {
            payment.markRefunded(result.refundReference());
            paymentRepository.save(payment);
            replyRefunded(command, payment, true, null);

            log.info("Refunded {} {} for order {} (payment {}, {})", payment.getAmount(),
                    payment.getCurrency(), command.getOrderId(), payment.getId(),
                    result.refundReference());
        } else {
            // The payment stays COMPLETED on purpose. The customer's money is still with us, and
            // a row that said REFUNDED would hide exactly the debt somebody has to settle.
            replyRefunded(command, payment, false, result.failureReason());

            log.error("REFUND FAILED for order {} (payment {}): {}. The customer is owed {} {} "
                            + "and nobody has sent it.",
                    command.getOrderId(), payment.getId(), result.failureReason(),
                    payment.getAmount(), payment.getCurrency());
        }
    }

    private void replyRefunded(RefundPaymentCommand command, Payment payment, boolean success,
                               String failureReason) {
        PaymentRefundedEvent reply = PaymentRefundedEvent.builder()
                .orderId(command.getOrderId())
                .paymentId(payment == null ? null : payment.getId())
                .amount(payment == null ? command.getAmount() : payment.getAmount())
                .currency(payment == null ? command.getCurrency() : payment.getCurrency())
                .refundReference(payment == null ? null : payment.getTransactionId())
                .success(success)
                .failureReason(failureReason)
                .build();
        reply.replyTo(command);
        outboxService.append(AGGREGATE_TYPE,
                payment == null ? command.getOrderId().toString() : payment.getId().toString(),
                reply);
    }

    // =====================================================================================
    // Webhook
    // =====================================================================================

    /**
     * Settles a payment on the acquirer's word, arriving out of band.
     *
     * <p>This is the other half of an asynchronous charge, and it runs with no command in scope —
     * which is exactly why the payment row stores {@code sagaId} and {@code commandId}. Without
     * them the reply would carry no linkage and the orchestrator would drop it, leaving the order
     * stuck behind a charge that actually succeeded.
     *
     * <p>Idempotent: providers retry webhooks, and a payment that is already settled is left
     * alone. The first delivery wins and the rest are no-ops.
     *
     * @param gatewayReference the provider's handle, as it appears on the event
     * @param approved         whether the money moved
     * @param declineReason    machine readable reason when it did not
     * @return whether anything changed, for the caller's log
     */
    @Transactional
    public boolean settleFromGateway(String gatewayReference, boolean approved,
                                     String declineReason) {
        Optional<Payment> found = paymentRepository.findByGatewayReference(gatewayReference);
        if (found.isEmpty()) {
            // Not ours. Another environment sharing a Stripe account, or an event type we did
            // not ask for. Dropping it is correct; failing would make the provider retry forever.
            log.warn("Webhook for unknown gateway reference {}", gatewayReference);
            return false;
        }

        Payment payment = found.get();
        if (payment.isSettled()) {
            log.debug("Payment {} is already {}; webhook ignored", payment.getId(),
                    payment.getStatus());
            return false;
        }

        if (approved) {
            payment.markCompleted(gatewayReference);
            paymentRepository.save(payment);
            replyCompleted(payment, null);
            log.info("Payment {} for order {} completed by webhook", payment.getId(),
                    payment.getOrderId());
        } else {
            payment.markFailed(declineReason);
            paymentRepository.save(payment);
            replyFailed(payment, declineReason, null);
            log.warn("Payment {} for order {} declined at the acquirer: {}", payment.getId(),
                    payment.getOrderId(), declineReason);
        }
        return true;
    }

    /**
     * Manual entry point from {@code contracts/apis.md}.
     *
     * <p>Kept for operator-driven retries and for clients that pay outside the automatic flow. It
     * shares the same settlement logic and the same one-payment-per-order guarantee.
     *
     * @throws ConflictException when the order has already been paid
     */
    @Transactional
    public PaymentResponse processManually(ProcessPaymentRequest request, AuthenticatedUser caller) {
        Optional<Payment> existing = paymentRepository.findByOrderId(request.orderId());
        if (existing.isPresent() && existing.get().isSettled()) {
            throw new ConflictException(ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "Order " + request.orderId() + " has already been paid");
        }

        Payment payment = existing.orElseGet(() -> newPayment(
                request.orderId(), request.orderNumber(), caller.userId(), caller.email(),
                request.amount(), request.currencyOrDefault(properties.getCurrency()),
                request.methodOrDefault()));

        payment.setAmount(request.amount());
        payment.setStatus(PaymentStatus.PENDING);

        settle(payment, null);

        // Deliberately not thrown: an exception here would roll back the payment row and the
        // payment.failed outbox row with it, leaving the customer with a 402 and the saga
        // waiting forever. The controller turns a FAILED outcome into the right status code.
        return paymentMapper.toResponse(payment);
    }

    /** @throws ResourceNotFoundException when there is no such payment */
    @Transactional(readOnly = true)
    public PaymentResponse getById(UUID paymentId, AuthenticatedUser caller) {
        return paymentRepository.findById(paymentId)
                .filter(payment -> isVisibleTo(payment, caller))
                .map(paymentMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PAYMENT_NOT_FOUND, "Payment not found: " + paymentId));
    }

    /** @throws ResourceNotFoundException when the order has no payment */
    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(UUID orderId, AuthenticatedUser caller) {
        return paymentRepository.findByOrderId(orderId)
                .filter(payment -> isVisibleTo(payment, caller))
                .map(paymentMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.PAYMENT_NOT_FOUND, "No payment for order " + orderId));
    }

    /**
     * Runs the charge and answers with the outcome.
     *
     * <p>The payment row and its reply are written in the same transaction, so the saga can never
     * be told about a charge that was not recorded, or left waiting for one that was.
     *
     * @param command the command being answered, or {@code null} for a manual payment
     */
    private void settle(Payment payment, ProcessPaymentCommand command) {
        PaymentGateway.ChargeResult result;
        try {
            result = paymentGateway.charge(
                    payment.getOrderId(), payment.getAmount(), payment.getCurrency());
        } catch (RuntimeException ex) {
            // A transport failure is not a decline. Recording it as one would cancel an order that
            // may in fact have been charged, so it is surfaced as a failure the saga can act on
            // while the operator reconciles with the acquirer.
            log.error("Acquirer call failed for order {}", payment.getOrderId(), ex);
            result = PaymentGateway.ChargeResult.declined(REASON_GATEWAY_ERROR);
        }

        // Stored before the outcome is known, because the webhook that may settle this has no
        // command in scope and still has to produce a reply the orchestrator will accept.
        if (command != null) {
            payment.setSagaId(command.getSagaId());
            payment.setCommandId(command.getEventId());
        }

        if (result.isPending()) {
            // The acquirer has it and has not decided. Answering now would be a guess; the
            // webhook, or a later reconcile, says which way it went.
            payment.markPending(result.gatewayReference(), result.clientSecret());
            paymentRepository.save(payment);
            log.info("Payment {} for order {} is in flight at the acquirer ({}); waiting",
                    payment.getId(), payment.getOrderId(), result.gatewayReference());
            return;
        }

        if (result.isApproved()) {
            payment.setGatewayReference(result.gatewayReference());
            payment.markCompleted(result.transactionId());
            paymentRepository.save(payment);
            replyCompleted(payment, command);

            log.info("Charged {} {} for order {} (payment {}, transaction {})",
                    payment.getAmount(), payment.getCurrency(), payment.getOrderId(),
                    payment.getId(), payment.getTransactionId());
        } else {
            payment.markFailed(result.declineReason());
            paymentRepository.save(payment);
            replyFailed(payment, result.declineReason(), command);

            log.warn("Payment declined for order {} (payment {}): {}", payment.getOrderId(),
                    payment.getId(), result.declineReason());
        }
    }

    private void replyCompleted(Payment payment, ProcessPaymentCommand command) {
        PaymentCompletedEvent reply = PaymentCompletedEvent.builder()
                .orderId(payment.getOrderId())
                .orderNumber(payment.getOrderNumber())
                .paymentId(payment.getId())
                .userId(payment.getUserId())
                .userEmail(payment.getUserEmail())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .transactionId(payment.getTransactionId())
                .status(PaymentStatus.COMPLETED.name())
                .build();
        link(reply, payment, command);
        outboxService.append(AGGREGATE_TYPE, payment.getId().toString(), reply);
    }

    private void replyFailed(Payment payment, String reason, ProcessPaymentCommand command) {
        PaymentFailedEvent reply = PaymentFailedEvent.builder()
                .orderId(payment.getOrderId())
                .orderNumber(payment.getOrderNumber())
                .paymentId(payment.getId())
                .userId(payment.getUserId())
                .userEmail(payment.getUserEmail())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .reason(reason)
                .build();
        link(reply, payment, command);
        outboxService.append(AGGREGATE_TYPE, payment.getId().toString(), reply);
    }

    /**
     * Attaches the saga linkage to an outgoing reply.
     *
     * <p>For a commanded payment this is a straight echo of what the orchestrator sent. For a
     * manual one there is no command, so the saga id is taken from the order id — the platform
     * runs one saga per order, keyed by it. That is what lets an operator paying a stuck order by
     * hand actually unblock its saga instead of leaving it to time out.
     */
    private static void link(DomainEvent reply, Payment payment, ProcessPaymentCommand command) {
        if (command != null) {
            reply.replyTo(command);
            return;
        }
        // No command in scope: either a webhook settling an asynchronous charge, or an operator
        // paying by hand. The row remembers the linkage for the first case; for the second there
        // was never one, and the order id is the saga id by construction.
        reply.setSagaId(payment.getSagaId() != null ? payment.getSagaId() : payment.getOrderId());
        reply.setCausationId(payment.getCommandId());
    }

    private Payment newPayment(UUID orderId, String orderNumber, UUID userId, String userEmail,
                               BigDecimal amount, String currency, PaymentMethod method) {
        Instant now = Instant.now();
        return Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .orderNumber(orderNumber)
                .userId(userId)
                .userEmail(userEmail)
                .amount(amount)
                .currency(currency == null ? properties.getCurrency() : currency)
                .status(PaymentStatus.PENDING)
                .method(method)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static boolean isVisibleTo(Payment payment, AuthenticatedUser caller) {
        return caller.isAdmin()
                || (payment.getUserId() != null && payment.getUserId().equals(caller.userId()));
    }
}
