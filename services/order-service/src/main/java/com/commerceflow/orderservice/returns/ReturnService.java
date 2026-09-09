package com.commerceflow.orderservice.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.common.event.RefundReturnCommand;
import com.commerceflow.common.event.RestockReturnedGoodsCommand;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.money.Money;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.returns.ReturnDtos.CreateReturnRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnItemRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnResponse;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnableLine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sending goods back, and getting paid for them.
 *
 * <h2>The refund figure is computed here and frozen</h2>
 *
 * <p>A line gives back what was actually charged for it — {@code unitPrice}, which already has any
 * coupon allocated into it — plus tax at the rate frozen on the order. Both are snapshotted onto
 * the return when it is raised, so a catalogue price change or an expiring campaign between asking
 * and refunding cannot alter what somebody gets.
 *
 * <p>Tax is recomputed on the returned quantity rather than apportioned out of the line's rounded
 * tax total. That is the same rule the order follows, and breaking it is how a cent goes missing:
 * dividing a rounded total by three gives three numbers that do not add back up to it.
 *
 * <h2>Nothing may come back twice</h2>
 *
 * <p>Every open or settled return counts against what is left of a line. A rejected or cancelled
 * one does not, because no goods moved — but an open request does, or a customer could ask twice
 * before anybody looked and be refunded twice.
 *
 * <h2>The refund is asked for, not taken</h2>
 *
 * <p>Order Service does not move money. It appends a command to its outbox in the same transaction
 * that marks the return {@code REFUND_PENDING}, and Payment Service answers on
 * {@code return.refunded}. There is no synchronous call and no service-to-service credential,
 * because the outbox already guarantees the command survives a crash and the reply already carries
 * the outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnService {

    private static final String AGGREGATE_TYPE = "RETURN";

    private final OrderRepository orders;
    private final ReturnRequestRepository returns;
    private final OutboxService outboxService;
    private final AuditService auditService;

    // =============================================================================== customer

    /**
     * What of an order may still be sent back.
     *
     * <p>Served before the customer chooses, so the form cannot offer a quantity that will be
     * refused.
     */
    @Transactional(readOnly = true)
    public List<ReturnableLine> returnable(UUID orderId, AuthenticatedUser caller) {
        Order order = readableOrder(orderId, caller);
        Map<UUID, Integer> claimed = returns.claimedQuantities(orderId);

        List<ReturnableLine> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            int already = claimed.getOrDefault(item.getId(), 0);
            lines.add(new ReturnableLine(
                    item.getId(),
                    item.getProductId(),
                    item.getProductName(),
                    item.getSku(),
                    item.getQuantity(),
                    already,
                    Math.max(0, item.getQuantity() - already),
                    refundForUnits(item, 1, order.getCurrency()),
                    order.getCurrency()));
        }
        return lines;
    }

    /** Raises a return request. */
    @Transactional
    public ReturnResponse request(UUID orderId, CreateReturnRequest request,
                                  AuthenticatedUser caller) {

        Order order = readableOrder(orderId, caller);

        // Only a finished order. One still being paid for is cancelled, not returned — that path
        // unwinds the saga and puts the stock back, which is a different and cheaper operation.
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "This order is " + order.getStatus() + ". An order that has not completed is "
                            + "cancelled rather than returned.");
        }

        Map<UUID, OrderItem> lines = new HashMap<>();
        order.getItems().forEach(item -> lines.put(item.getId(), item));
        Map<UUID, Integer> claimed = returns.claimedQuantities(orderId);

        ReturnRequest returnRequest = ReturnRequest.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .status(ReturnStatus.REQUESTED)
                .reason(request.reason().trim())
                .currency(order.getCurrency())
                .refundAmount(BigDecimal.ZERO)
                .requestedAt(Instant.now())
                .items(new ArrayList<>())
                .build();

        BigDecimal total = Money.zero(order.getCurrency());
        int fullyReturnedLines = 0;

        for (ReturnItemRequest wanted : request.items()) {
            OrderItem line = lines.get(wanted.orderItemId());
            if (line == null) {
                throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That line is not on this order: " + wanted.orderItemId());
            }

            int already = claimed.getOrDefault(line.getId(), 0);
            int remaining = line.getQuantity() - already;
            if (wanted.quantity() > remaining) {
                throw new ConflictException(ErrorCode.CONFLICT,
                        "You ordered " + line.getQuantity() + " of " + line.getProductName()
                                + (already > 0 ? " and have already returned " + already : "")
                                + ", so at most " + Math.max(0, remaining) + " can come back.");
            }

            BigDecimal lineRefund = refundForUnits(line, wanted.quantity(), order.getCurrency());
            total = total.add(lineRefund);

            if (wanted.quantity() == remaining && already == 0) {
                fullyReturnedLines++;
            }

            returnRequest.addItem(ReturnRequestItem.builder()
                    .id(UUID.randomUUID())
                    .orderItemId(line.getId())
                    .productId(line.getProductId())
                    .productName(line.getProductName())
                    .sku(line.getSku())
                    .quantity(wanted.quantity())
                    .refundAmount(lineRefund)
                    .build());
        }

        // Delivery comes back only when the entire order does, which is what the returns policy
        // promises. A customer keeping one item of three has still had the parcel delivered.
        boolean wholeOrder = fullyReturnedLines == order.getItems().size() && claimed.isEmpty();
        if (wholeOrder && order.getShippingAmount() != null) {
            total = total.add(order.getShippingAmount());
            returnRequest.setRefundShipping(true);
        }

        returnRequest.setRefundAmount(Money.round(total, order.getCurrency()));
        returns.save(returnRequest);

        log.info("Return {} raised on order {} for {} {}", returnRequest.getId(),
                order.getOrderNumber(), returnRequest.getRefundAmount(), order.getCurrency());

        return ReturnResponse.of(returnRequest);
    }

    /** A customer calling it off before anybody has decided. */
    @Transactional
    public ReturnResponse cancel(UUID returnId, AuthenticatedUser caller) {
        ReturnRequest returnRequest = readable(returnId, caller);

        if (!returnRequest.getStatus().isBeforeDispatch()) {
            // Once approved the goods are on their way back, and cancelling would leave a parcel
            // arriving that nothing expects.
            throw new ConflictException(ErrorCode.CONFLICT,
                    "This return is " + returnRequest.getStatus()
                            + " and can no longer be called off. Contact support.");
        }

        returnRequest.cancel();
        returns.save(returnRequest);
        return ReturnResponse.of(returnRequest);
    }

    @Transactional(readOnly = true)
    public List<ReturnResponse> forOrder(UUID orderId, AuthenticatedUser caller) {
        readableOrder(orderId, caller);
        return returns.findByOrderIdOrderByRequestedAtDesc(orderId).stream()
                .map(ReturnResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ReturnResponse> mine(AuthenticatedUser caller) {
        return returns.findByUserIdOrderByRequestedAtDesc(caller.userId()).stream()
                .map(ReturnResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public ReturnResponse getById(UUID returnId, AuthenticatedUser caller) {
        return ReturnResponse.of(readable(returnId, caller));
    }

    // ============================================================================== operator

    @Transactional(readOnly = true)
    public PageResponse<ReturnResponse> queue(ReturnStatus status, int page, int size) {
        Page<ReturnRequest> found = status == null
                ? returns.findAllByOrderByRequestedAtDesc(PageRequest.of(page, size))
                : returns.findByStatusOrderByRequestedAtAsc(status, PageRequest.of(page, size));

        return PageResponse.of(found.getContent().stream().map(ReturnResponse::of).toList(),
                page, size, found.getTotalElements());
    }

    @Transactional
    public ReturnResponse approve(UUID returnId, String note, AuthenticatedUser operator) {
        ReturnRequest returnRequest = require(returnId);
        requireStatus(returnRequest, ReturnStatus.REQUESTED, "approved");

        returnRequest.approve(operator.userId(), note);
        returns.save(returnRequest);

        auditService.record(operator, "RETURN_APPROVED", "RETURN", returnId,
                "Approved a return on order " + returnRequest.getOrderNumber()
                        + " worth " + returnRequest.getRefundAmount() + " "
                        + returnRequest.getCurrency());

        // The customer has been told to post something back and does not yet know where to. This
        // is the message that answers that, and without it the approval is useless to them.
        notify(returnRequest, "RETURN_APPROVED",
                "Your return for order " + returnRequest.getOrderNumber() + " is approved",
                note);

        return ReturnResponse.of(returnRequest);
    }

    @Transactional
    public ReturnResponse reject(UUID returnId, String note, AuthenticatedUser operator) {
        ReturnRequest returnRequest = require(returnId);
        requireStatus(returnRequest, ReturnStatus.REQUESTED, "rejected");

        returnRequest.reject(operator.userId(), note);
        returns.save(returnRequest);

        auditService.record(operator, "RETURN_REJECTED", "RETURN", returnId,
                "Refused a return on order " + returnRequest.getOrderNumber());

        // A refusal nobody is told about reads to the customer as being ignored, and the note is
        // the only explanation they will ever get.
        notify(returnRequest, "RETURN_REJECTED",
                "About your return for order " + returnRequest.getOrderNumber(),
                note);

        return ReturnResponse.of(returnRequest);
    }

    /**
     * The goods are back and have been checked.
     *
     * <h2>Receiving and restocking are one decision, and it has to be made</h2>
     *
     * <p>Not everything that comes back can be sold again. A change of mind returns to the shelf;
     * a cracked screen returns to the warehouse and never to a customer. Nobody but the person
     * holding the box can tell, so {@code backOnSale} has no default — guessing true sells the
     * next customer something broken, and guessing false quietly writes off good stock.
     *
     * <p>Before this existed, returned goods were refunded and then vanished from inventory
     * entirely: still on a shelf, and invisible to the shop. The stock figure drifted down by
     * exactly the number of items customers sent back.
     */
    @Transactional
    public ReturnResponse markReceived(UUID returnId, boolean backOnSale,
                                       AuthenticatedUser operator) {

        ReturnRequest returnRequest = require(returnId);
        requireStatus(returnRequest, ReturnStatus.APPROVED, "marked as received");

        returnRequest.markReceived(backOnSale);
        returns.save(returnRequest);

        if (backOnSale) {
            // Explicit lines, not "the whole order". Inventory Service has a command that
            // restocks an entire reservation, and it is for cancellation — using it here would
            // put back items the customer kept and close the reservation against a second
            // return.
            outboxService.append(AGGREGATE_TYPE, returnRequest.getOrderId().toString(),
                    RestockReturnedGoodsCommand.builder()
                            .returnId(returnRequest.getId())
                            .orderId(returnRequest.getOrderId())
                            .orderNumber(returnRequest.getOrderNumber())
                            .reason(returnRequest.getReason())
                            .lines(returnRequest.getItems().stream()
                                    .map(item -> OrderLineItem.builder()
                                            .productId(item.getProductId())
                                            .sku(item.getSku())
                                            .productName(item.getProductName())
                                            .quantity(item.getQuantity())
                                            .build())
                                    .toList())
                            .build());
        }

        auditService.record(operator, "RETURN_RECEIVED", "RETURN", returnId,
                "Goods received for a return on order " + returnRequest.getOrderNumber()
                        + (backOnSale ? "; put back on sale" : "; written off, not resold"));

        return ReturnResponse.of(returnRequest);
    }

    /**
     * Asks Payment Service for the money.
     *
     * <p>The command goes into the outbox in the same transaction that moves the return to
     * {@code REFUND_PENDING}, so the two cannot disagree: either both happened or neither did.
     * Payment Service treats the return id as its idempotency key, so a republished command
     * refunds once.
     */
    @Transactional
    public ReturnResponse refund(UUID returnId, AuthenticatedUser operator) {
        ReturnRequest returnRequest = require(returnId);

        if (returnRequest.getStatus() == ReturnStatus.REFUND_PENDING) {
            // Not an error. Somebody pressed it twice, or two people are looking at the same
            // queue. The command is already out and the return already says so.
            log.info("Return {} is already awaiting a refund", returnId);
            return ReturnResponse.of(returnRequest);
        }

        requireStatus(returnRequest, ReturnStatus.RECEIVED, "refunded");

        returnRequest.markRefundPending();
        returns.save(returnRequest);

        outboxService.append(AGGREGATE_TYPE, returnRequest.getOrderId().toString(),
                RefundReturnCommand.builder()
                        .returnId(returnRequest.getId())
                        .orderId(returnRequest.getOrderId())
                        .orderNumber(returnRequest.getOrderNumber())
                        .amount(returnRequest.getRefundAmount())
                        .currency(returnRequest.getCurrency())
                        .reason("Return " + returnRequest.getOrderNumber())
                        .build());

        auditService.record(operator, "RETURN_REFUND_REQUESTED", "RETURN", returnId,
                "Asked for " + returnRequest.getRefundAmount() + " "
                        + returnRequest.getCurrency() + " back on order "
                        + returnRequest.getOrderNumber());

        return ReturnResponse.of(returnRequest);
    }

    // =============================================================================== internals

    /**
     * Tells the customer what has happened to their return.
     *
     * <h2>Three moments, not six</h2>
     *
     * <p>Approved, refused, refunded. Those are the points at which the customer either has to do
     * something or has been waiting for an answer. "We received your parcel" is not one of them:
     * it arrives a day before the refund and teaches people that mail from the shop can be
     * ignored, which is expensive the day one of these messages matters.
     *
     * <h2>Through the outbox, like everything else</h2>
     *
     * <p>Appended in the same transaction as the state change, so an approval that commits is an
     * approval the customer hears about. Notification Service decides how to deliver it.
     */
    private void notify(ReturnRequest request, String template, String subject, String note) {
        if (request.getUserEmail() == null) {
            log.warn("Return {} changed but its order has no address to tell", request.getId());
            return;
        }

        Map<String, String> params = new HashMap<>();
        params.put("orderNumber", request.getOrderNumber());
        params.put("refundAmount", request.getRefundAmount() + " " + request.getCurrency());
        // Empty rather than absent when there is no note: a template rendering the literal word
        // "null" at a customer reads as a bug, and they are right.
        params.put("note", note == null || note.isBlank() ? "" : note.trim());

        outboxService.append(AGGREGATE_TYPE, request.getId().toString(),
                NotificationSendEvent.builder()
                        .userId(request.getUserId())
                        .recipient(request.getUserEmail())
                        .templateCode(template)
                        .subject(subject)
                        .params(params)
                        .referenceId(request.getOrderId())
                        .build());
    }

    /**
     * What a number of units of a line gives back.
     *
     * <p>Net at the price actually charged, plus tax at the frozen rate, rounded once. For a whole
     * line this reproduces the tax the order recorded exactly, because it is the same calculation.
     */
    private BigDecimal refundForUnits(OrderItem line, int units, String currency) {
        BigDecimal net = line.getUnitPrice().multiply(BigDecimal.valueOf(units));

        BigDecimal tax = BigDecimal.ZERO;
        if (line.getTaxRate() != null) {
            tax = net.multiply(line.getTaxRate())
                    .setScale(Money.scaleOf(currency), RoundingMode.HALF_UP);
        }

        return Money.round(net.add(tax), currency);
    }

    private Order readableOrder(UUID orderId, AuthenticatedUser caller) {
        return orders.findById(orderId)
                .filter(order -> caller.isAdmin() || order.getUserId().equals(caller.userId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
    }

    private ReturnRequest readable(UUID returnId, AuthenticatedUser caller) {
        return returns.findById(returnId)
                .filter(request -> caller.isAdmin() || request.getUserId().equals(caller.userId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Return not found: " + returnId));
    }

    private ReturnRequest require(UUID returnId) {
        return returns.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Return not found: " + returnId));
    }

    private void requireStatus(ReturnRequest request, ReturnStatus expected, String action) {
        if (request.getStatus() != expected) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "A return can only be " + action + " while it is " + expected
                            + ". This one is " + request.getStatus() + ".");
        }
    }

    /** Guard for callers that reach this service without a principal. */
    public static AuthenticatedUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return caller;
    }
}
