package com.commerceflow.paymentservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One payment attempt for one order.
 *
 * <p>The unique index on {@code order_id} is the strongest guarantee in this service: whatever
 * happens upstream — a redelivered {@code inventory.reserved}, a manual retry through the REST
 * API, two replicas racing — a customer can only ever be charged once per order.
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_user", columnList = "user_id"),
        @Index(name = "idx_payments_status", columnList = "status"),
        @Index(name = "idx_payments_created", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "order_number", length = 32)
    private String orderNumber;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private PaymentMethod method;

    /** Reference returned by the acquirer; the key for reconciliation and refunds. */
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * The saga this payment belongs to, and the command it answers.
     *
     * <p>Stored rather than held in scope because a real acquirer settles asynchronously: the
     * webhook that finally says "succeeded" arrives with no command anywhere near it, and a reply
     * without this linkage is one the orchestrator drops.
     */
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "command_id")
    private UUID commandId;

    /** The provider's handle, e.g. a Stripe PaymentIntent id. Webhook lookup key. */
    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    /**
     * Authorises the browser to complete this one charge.
     *
     * <p>Returned only to the customer the payment belongs to, and only while it is still
     * outstanding — once the charge settles it grants nothing, but there is no reason to keep
     * handing it out.
     */
    @Column(name = "client_secret", length = 256)
    private String clientSecret;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markCompleted(String acquirerTransactionId) {
        this.status = PaymentStatus.COMPLETED;
        this.transactionId = acquirerTransactionId;
        this.failureReason = null;
        this.processedAt = Instant.now();
        this.updatedAt = this.processedAt;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.processedAt = Instant.now();
        this.updatedAt = this.processedAt;
    }

    /** Records that the acquirer has taken the charge but not decided yet. */
    public void markPending(String gatewayReference, String clientSecret) {
        this.status = PaymentStatus.PENDING;
        this.gatewayReference = gatewayReference;
        this.clientSecret = clientSecret;
        this.updatedAt = Instant.now();
    }

    /** Records that the money has been sent back. */
    public void markRefunded(String reference) {
        this.status = PaymentStatus.REFUNDED;
        this.transactionId = reference == null ? this.transactionId : reference;
        this.updatedAt = Instant.now();
    }

    /** @return whether money actually moved and could still be given back. */
    public boolean isRefundable() {
        return status == PaymentStatus.COMPLETED;
    }

    public boolean isSettled() {
        return status == PaymentStatus.COMPLETED;
    }
}
