package com.commerceflow.orderservice.returns;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request to send goods back.
 *
 * <p>Its own aggregate with its own lifecycle, running alongside the order rather than inside it —
 * the same arrangement as a shipment, and for the same reason: an order is a finished piece of
 * business and a return is a new one that refers to it.
 *
 * <p>The money is frozen when the request is raised. A catalogue price change between asking and
 * refunding must not alter what somebody gets back, and neither must a coupon expiring.
 */
@Entity
@Table(name = "return_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReturnStatus status;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "refund_shipping", nullable = false)
    private boolean refundShipping;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "refund_reference", length = 128)
    private String refundReference;

    @Column(name = "refund_failure", length = 255)
    private String refundFailure;

    /**
     * Whether the goods went back on sale.
     *
     * <p>{@code null} until they arrive. A {@link Boolean} rather than a primitive precisely so
     * that "not received yet" and "received and written off" are different values — collapsing
     * them would make a write-off indistinguishable from a return nobody has opened.
     */
    @Column(name = "restocked")
    private Boolean restocked;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Builder.Default
    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<ReturnRequestItem> items = new ArrayList<>();

    public void addItem(ReturnRequestItem item) {
        item.setReturnRequest(this);
        items.add(item);
    }

    /** Approved: the customer may send the goods back. */
    public void approve(UUID operator, String note) {
        this.status = ReturnStatus.APPROVED;
        this.decidedAt = Instant.now();
        this.decidedBy = operator;
        this.decisionNote = note;
    }

    public void reject(UUID operator, String note) {
        this.status = ReturnStatus.REJECTED;
        this.decidedAt = Instant.now();
        this.decidedBy = operator;
        this.decisionNote = note;
    }

    /**
     * The goods are back and have been checked.
     *
     * @param backOnSale what the person who opened the box decided. Required rather than
     *     defaulted: defaulting to true puts broken things in front of the next customer, and
     *     defaulting to false silently writes off stock that was perfectly good.
     */
    public void markReceived(boolean backOnSale) {
        this.status = ReturnStatus.RECEIVED;
        this.restocked = backOnSale;
        this.receivedAt = Instant.now();
        // Cleared, because whatever went wrong last time is no longer the current state of this
        // return. Leaving it would show a stale failure next to a refund that is about to work.
        this.refundFailure = null;
    }

    /** The refund command is out; the answer has not arrived. */
    public void markRefundPending() {
        this.status = ReturnStatus.REFUND_PENDING;
        this.refundFailure = null;
    }

    public void markRefunded(BigDecimal amount, String reference) {
        this.status = ReturnStatus.REFUNDED;
        this.refundedAt = Instant.now();
        this.refundReference = reference;
        this.refundFailure = null;
        if (amount != null) {
            // What actually went back, which the payment side may have capped below what was
            // asked for. Recording the request rather than the outcome would leave the shop
            // believing it had paid more than it did.
            this.refundAmount = amount;
        }
    }

    /**
     * The refund did not go through.
     *
     * <p>Back to {@code RECEIVED} rather than a dead state: the goods really are here, the money
     * really is owed, and somebody has to be able to try again. A terminal failure state would
     * mean the only way out was the database.
     */
    public void markRefundFailed(String failureReason) {
        this.status = ReturnStatus.RECEIVED;
        this.refundFailure = failureReason;
    }

    public void cancel() {
        this.status = ReturnStatus.CANCELLED;
    }
}
