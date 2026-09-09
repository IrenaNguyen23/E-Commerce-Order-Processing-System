package com.commerceflow.paymentservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One movement of money back to a customer.
 *
 * <p>What has been refunded on a payment is the sum of the successful rows here, and what may
 * still be refunded is the payment less that sum. Both are facts that can be recomputed, rather
 * than a flag that has to be kept true.
 *
 * <p>Failed attempts are kept. A refund that did not go through is a debt the shop owes, and this
 * row is the only evidence anybody ever tried.
 */
@Entity
@Table(name = "payment_refunds")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRefund {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", length = 255)
    private String reason;

    /** The acquirer's reference. Null when the attempt failed. */
    @Column(name = "external_reference", length = 128)
    private String externalReference;

    @Column(name = "succeeded", nullable = false)
    private boolean succeeded;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * The return this settles, and the idempotency key.
     *
     * <p>A business identifier rather than a message id on purpose: the same return must refund
     * once however many times the command is delivered or republished.
     */
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
