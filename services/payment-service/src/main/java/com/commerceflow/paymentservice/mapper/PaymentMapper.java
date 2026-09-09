package com.commerceflow.paymentservice.mapper;

import org.springframework.stereotype.Component;

import com.commerceflow.paymentservice.dto.PaymentResponse;
import com.commerceflow.paymentservice.entity.Payment;

/** Maps the payment aggregate onto its public projection. */
@Component
public class PaymentMapper {

    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getOrderNumber(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getMethod() == null ? null : payment.getMethod().name(),
                payment.getTransactionId(),
                payment.getFailureReason(),
                // Only while there is still something to pay. A settled charge's secret grants
                // nothing, and handing out a credential with no remaining purpose is a habit
                // worth not forming.
                payment.isSettled() ? null : payment.getClientSecret(),
                payment.getCreatedAt(),
                payment.getProcessedAt());
    }
}
