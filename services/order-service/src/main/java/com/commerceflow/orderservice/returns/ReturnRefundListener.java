package com.commerceflow.orderservice.returns;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.ReturnRefundedEvent;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.common.outbox.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What Payment Service made of a refund request.
 *
 * <h2>Both outcomes arrive here</h2>
 *
 * <p>A refund that failed is not silence — it is the same event with {@code success} false. That
 * matters: a return left in {@code REFUND_PENDING} forever would mean both "the money is on its
 * way" and "it failed and nobody noticed", and nobody could tell which.
 *
 * <p>A failure puts the return back to {@code RECEIVED} with the provider's reason on it. The
 * goods really are here and the money really is owed, so the only useful state is one somebody can
 * act on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.RETURN_REFUNDED,
        groupId = ReturnRefundListener.CONSUMER_GROUP,
        id = "order-return-refunds")
public class ReturnRefundListener {

    static final String CONSUMER_GROUP = "order-service-returns";

    private final ReturnRequestRepository returns;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final AuditService auditService;

    @KafkaHandler
    @Transactional
    public void onRefunded(ReturnRefundedEvent event) {
        if (!idempotencyService.claim(CONSUMER_GROUP, event.getEventId(), event.getEventType())) {
            return;
        }

        Optional<ReturnRequest> found = returns.findById(event.getReturnId());
        if (found.isEmpty()) {
            // A reply for a return this service does not have. Logged rather than thrown: the
            // money has already moved, and stopping the partition would block every later refund
            // from being recorded.
            log.error("Refund reply for unknown return {} (order {}). Money may have moved.",
                    event.getReturnId(), event.getOrderId());
            return;
        }

        ReturnRequest request = found.get();

        if (request.getStatus() == ReturnStatus.REFUNDED) {
            log.info("Return {} is already refunded; ignoring a repeated reply", request.getId());
            return;
        }

        if (event.isSuccess()) {
            request.markRefunded(event.getAmount(), event.getRefundReference());
            returns.save(request);

            auditService.recordSystem("RETURN_REFUNDED", "RETURN", request.getId(),
                    "Refunded " + event.getAmount() + " " + event.getCurrency()
                            + " on order " + request.getOrderNumber()
                            + " (" + event.getRefundReference() + ")");

            // The one message the customer is actually waiting for. Sent from here rather than
            // from the button that asked for the refund, because until this reply arrives the
            // money has not moved and telling them it had would be a lie.
            if (request.getUserEmail() != null) {
                Map<String, String> params = new HashMap<>();
                params.put("orderNumber", request.getOrderNumber());
                params.put("refundAmount", event.getAmount() + " " + event.getCurrency());
                params.put("note", "");

                outboxService.append("RETURN", request.getId().toString(),
                        NotificationSendEvent.builder()
                                .userId(request.getUserId())
                                .recipient(request.getUserEmail())
                                .templateCode("RETURN_REFUNDED")
                                .subject("Your refund for order " + request.getOrderNumber())
                                .params(params)
                                .referenceId(request.getOrderId())
                                .build());
            }

            log.info("Return {} refunded: {} {} ({})", request.getId(), event.getAmount(),
                    event.getCurrency(), event.getRefundReference());
        } else {
            request.markRefundFailed(event.getFailureReason());
            returns.save(request);

            auditService.recordSystem("RETURN_REFUND_FAILED", "RETURN", request.getId(),
                    "Refund failed on order " + request.getOrderNumber() + ": "
                            + event.getFailureReason());

            log.error("REFUND FAILED for return {} on order {}: {}. The customer is owed {} {} "
                            + "and nobody has sent it.",
                    request.getId(), request.getOrderNumber(), event.getFailureReason(),
                    request.getRefundAmount(), request.getCurrency());
        }
    }

    /** Anything else: ignored rather than allowed to stop the partition. */
    @KafkaHandler(isDefault = true)
    public void onOther(Object payload) {
        log.debug("Ignoring {} on {}", payload.getClass().getSimpleName(),
                KafkaTopics.RETURN_REFUNDED);
    }
}
