package com.commerceflow.orderservice.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.saga.OrderSagaOrchestrator;
import com.commerceflow.orderservice.saga.entity.SagaInstance;
import com.commerceflow.orderservice.saga.entity.SagaState;
import com.commerceflow.orderservice.saga.repository.SagaInstanceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cancelling an order.
 *
 * <p>Thin on purpose: it decides <em>whether</em> a cancellation is allowed and who is allowed to
 * ask, then hands the actual unwinding to the orchestrator. What has to be undone, and in what
 * order, is the saga's business and lives with the rest of the flow.
 *
 * <h2>Not while the saga is mid-flight</h2>
 *
 * <p>The single most important rule here. An order whose saga is still running has a command
 * outstanding — a charge being authorised, stock being reserved — and reopening the saga for a
 * cancellation would put a second command in flight against the same step. The pending reply then
 * hits the step guard and is dropped, which sounds harmless until the reply it dropped was
 * "payment succeeded" and the refund has already been issued for a charge the system has no
 * record of.
 *
 * <p>So a mid-flight order is refused with "try again in a moment", which is honest: the saga
 * settles in seconds, and the customer can cancel then.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaRepository;
    private final OrderSagaOrchestrator orchestrator;
    private final OrderMapper orderMapper;
    private final CouponService couponService;
    private final AuditService auditService;

    /**
     * Cancels an order on behalf of the caller.
     *
     * @param reason free text from the person cancelling; recorded and shown to the customer
     * @throws ResourceNotFoundException when the order does not exist, or is not the caller's
     * @throws ConflictException when it is already cancelled, or its saga is still running
     */
    @Transactional
    public OrderResponse cancel(UUID orderId, String reason, AuthenticatedUser caller) {
        Order order = orderRepository.findById(orderId)
                .filter(candidate -> isVisibleTo(candidate, caller))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new ConflictException(ErrorCode.ORDER_NOT_MODIFIABLE,
                    "Order " + order.getOrderNumber() + " is already cancelled");
        }

        SagaInstance saga = sagaRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "Order " + order.getOrderNumber() + " has no saga and cannot be unwound "
                                + "automatically. This needs an operator."));

        requireSettled(saga, order, caller);

        String recorded = reason == null || reason.isBlank()
                ? "Cancelled by " + (caller.isAdmin() ? "an administrator" : "the customer")
                : reason.trim();

        orchestrator.cancel(saga, order, recorded);

        // The code goes back to its campaign. A customer who cancelled has not spent their
        // coupon, and a code that quietly stops working after a cancelled order is
        // indistinguishable, from the outside, from a code that never worked.
        couponService.release(order.getId());

        // In the same transaction as the cancellation. An audit trail written separately
        // records things that were rolled back and misses things that were not, and both
        // failures point the wrong way during an investigation.
        auditService.record(caller, "ORDER_CANCELLED", "ORDER", order.getId(),
                "Cancelled " + order.getOrderNumber() + ": " + recorded);

        log.info("Order {} cancelled by {} ({})", order.getOrderNumber(), caller.userId(),
                recorded);
        return orderMapper.toResponse(order);
    }

    /**
     * Refuses to cancel an order the saga has not finished with.
     *
     * <p>A parked saga is the one exception, and only for an administrator: it is already stopped
     * and waiting for a human, so there is no in-flight command to collide with — but whoever
     * cancels it has to know what the payment step was doing when it stalled.
     */
    private void requireSettled(SagaInstance saga, Order order, AuthenticatedUser caller) {
        // Checked before the isRunning test, because STALLED is not "running" — it is stopped
        // and waiting for a human, which is a different situation with a different answer.
        if (saga.getState() == SagaState.STALLED) {
            if (!caller.isAdmin()) {
                throw new ConflictException(ErrorCode.ORDER_NOT_MODIFIABLE,
                        "Order " + order.getOrderNumber() + " has run into a problem and is being "
                                + "looked at. Please contact support rather than cancelling it.");
            }
            log.warn("Administrator {} is cancelling order {}, whose saga is parked at {}. "
                            + "Verify the payment state before trusting the refund.",
                    caller.userId(), order.getOrderNumber(), saga.getCurrentStep());
            return;
        }

        if (!saga.getState().isRunning()) {
            return;
        }

        throw new ConflictException(ErrorCode.ORDER_NOT_MODIFIABLE,
                "Order " + order.getOrderNumber() + " is still being processed. "
                        + "This usually takes a few seconds — please try again in a moment.");
    }

    private static boolean isVisibleTo(Order order, AuthenticatedUser caller) {
        return caller.isAdmin()
                || (order.getUserId() != null && order.getUserId().equals(caller.userId()));
    }
}
