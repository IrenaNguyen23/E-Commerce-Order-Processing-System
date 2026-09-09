package com.commerceflow.paymentservice.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.commerceflow.paymentservice.entity.PaymentRefund;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, UUID> {

    /**
     * The refund already recorded for a return, if there is one.
     *
     * <p>Checked before doing anything, so a redelivered command answers from the record instead
     * of sending the money a second time. The unique constraint is the real guarantee; this is
     * what turns a would-be constraint violation into a correct answer.
     */
    Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

    List<PaymentRefund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    /**
     * How much has actually gone back to the customer on this payment.
     *
     * <p>Successful rows only. A failed attempt moved no money, and counting it would make the
     * shop believe it had paid a debt it still owes.
     */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0) FROM PaymentRefund r
             WHERE r.paymentId = :paymentId AND r.succeeded = true
            """)
    BigDecimal totalRefunded(@Param("paymentId") UUID paymentId);
}
