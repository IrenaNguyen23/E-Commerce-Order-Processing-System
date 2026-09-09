package com.commerceflow.orderservice.fulfilment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Getting parcels to people.
 *
 * <h2>Fulfilment starts where the saga stops</h2>
 *
 * <p>A shipment can only be created for an order the saga has already finished with. That is not a
 * formality: an order still being paid for has stock that is reserved rather than sold, and
 * dispatching against a reservation means the warehouse has sent something the shop may still have
 * to refund.
 *
 * <h2>Nothing here touches the order's status</h2>
 *
 * <p>An order stays {@code COMPLETED} while its parcel moves from {@code PENDING} to
 * {@code DELIVERED}. Two lifecycles, two clocks, and folding them together would mean loosening
 * the saga's transition guard — the thing that stops a late saga reply moving a finished order
 * backwards. See {@link ShipmentStatus} for the longer version.
 *
 * <h2>The customer is told about some changes and not others</h2>
 *
 * <p>Dispatch, a failed attempt, delivery and a return get an email. Picking does not, and neither
 * does a correction that leaves the parcel where it already was. An email per internal state change
 * trains people to ignore emails from the shop, which costs more than the one they needed to read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private static final String AGGREGATE_TYPE = "SHIPMENT";

    private final ShipmentRepository shipments;
    private final OrderRepository orders;
    private final OutboxService outboxService;
    private final AuditService auditService;

    /**
     * Opens a shipment for a completed order.
     *
     * @throws ConflictException when the order is not finished, or already has a live shipment
     */
    @Transactional
    public ShipmentResponse create(UUID orderId, CreateShipmentRequest request,
            AuthenticatedUser operator) {

        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.COMPLETED) {
            // An order still being paid for has stock reserved, not sold. Dispatching against a
            // reservation means shipping something that may still have to be refunded.
            throw new ConflictException(ErrorCode.ORDER_NOT_MODIFIABLE,
                    "Order " + order.getOrderNumber() + " is " + order.getStatus()
                            + " and cannot be shipped yet");
        }

        shipments.findByOrderId(orderId).stream()
                .filter(existing -> !existing.getStatus().isFinished())
                .findFirst()
                .ifPresent(existing -> {
                    throw new ConflictException(ErrorCode.CONFLICT,
                            "Order " + order.getOrderNumber() + " already has a shipment ("
                                    + existing.getStatus() + ")");
                });

        Instant now = Instant.now();
        Shipment shipment = Shipment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .status(ShipmentStatus.PENDING)
                .carrier(trimToNull(request.carrier()))
                .trackingNumber(trimToNull(request.trackingNumber()))
                .trackingUrl(trimToNull(request.trackingUrl()))
                .destination(order.getShippingAddress())
                .promisedBy(promisedBy(order, now))
                .createdAt(now)
                .updatedAt(now)
                .events(new java.util.ArrayList<>())
                .build();

        shipment.moveTo(ShipmentStatus.PENDING, "Shipment created", null, operator.userId());

        log.info("Opened shipment for order {} ({})", order.getOrderNumber(),
                shipment.getCarrier());
        return ShipmentResponse.of(shipments.save(shipment));
    }

    /**
     * Records a change of state.
     *
     * <p>Every call appends an event, including one that repeats the current state — a carrier
     * scanning a parcel twice in the same depot is information, not noise, and dropping it makes
     * the history less useful than the timestamps a courier can produce.
     */
    @Transactional
    public ShipmentResponse advance(UUID shipmentId, UpdateShipmentRequest request,
            AuthenticatedUser operator) {

        Shipment shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Shipment not found: " + shipmentId));

        ShipmentStatus previous = shipment.getStatus();
        shipment.moveTo(request.status(), trimToNull(request.note()),
                trimToNull(request.location()), operator.userId());

        if (request.trackingNumber() != null) {
            shipment.setTrackingNumber(trimToNull(request.trackingNumber()));
        }
        if (request.trackingUrl() != null) {
            shipment.setTrackingUrl(trimToNull(request.trackingUrl()));
        }
        if (request.carrier() != null) {
            shipment.setCarrier(trimToNull(request.carrier()));
        }

        Shipment saved = shipments.save(shipment);

        // Told only when something changed and the change is one a customer cares about. An
        // email per internal state change teaches people to ignore mail from the shop.
        if (previous != saved.getStatus() && saved.getStatus().isWorthTelling()) {
            notify(saved);
        }

        auditService.record(operator, "SHIPMENT_" + saved.getStatus(), "SHIPMENT", shipmentId,
                "Order " + saved.getOrderNumber() + ": " + previous + " to " + saved.getStatus());

        log.info("Shipment {} moved {} -> {}", shipmentId, previous, saved.getStatus());
        return ShipmentResponse.of(saved);
    }

    /** Every shipment for an order. Usually one, occasionally two after a return. */
    @Transactional(readOnly = true)
    public List<ShipmentResponse> forOrder(UUID orderId, AuthenticatedUser caller) {
        Order order = orders.findById(orderId)
                .filter(candidate -> caller.isAdmin()
                        || candidate.getUserId().equals(caller.userId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId));

        return shipments.findByOrderId(order.getId()).stream()
                .map(ShipmentResponse::of)
                .toList();
    }

    /** The warehouse queue: everything in one state, oldest first. */
    @Transactional(readOnly = true)
    public PageResponse<ShipmentResponse> queue(ShipmentStatus status, int page, int size) {
        Page<Shipment> found = shipments.findByStatus(status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));

        return PageResponse.of(found.getContent().stream().map(ShipmentResponse::of).toList(),
                page, size, found.getTotalElements());
    }

    /** Tracking lookup, for a customer who has the number and not the order. */
    @Transactional(readOnly = true)
    public ShipmentResponse track(String trackingNumber, AuthenticatedUser caller) {
        Shipment shipment = shipments.findByTrackingNumber(trackingNumber)
                .filter(found -> caller.isAdmin() || found.getUserId().equals(caller.userId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No shipment with that tracking number"));

        return ShipmentResponse.of(shipment);
    }

    // =====================================================================================

    /**
     * When the customer was told to expect it.
     *
     * <p>From the delivery window quoted at checkout, so a parcel that is late is late against
     * what was actually promised rather than against a target invented afterwards. Working days
     * are approximated as calendar days — a real carrier integration would know about weekends and
     * holidays, and inventing that arithmetic here would be a guess dressed as a commitment.
     */
    private static Instant promisedBy(Order order, Instant from) {
        Integer maxDays = order.getDeliveryMaxDays();
        return maxDays == null || maxDays <= 0 ? null : from.plus(maxDays, ChronoUnit.DAYS);
    }

    private void notify(Shipment shipment) {
        Order order = orders.findById(shipment.getOrderId()).orElse(null);
        if (order == null || order.getUserEmail() == null) {
            log.warn("Shipment {} changed to {} but its order has no address to tell",
                    shipment.getId(), shipment.getStatus());
            return;
        }

        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("orderNumber", order.getOrderNumber());
        params.put("carrierLine", shipment.getCarrier() == null
                ? ""
                : " with " + shipment.getCarrier());
        params.put("trackingLine", trackingLine(shipment));

        outboxService.append(AGGREGATE_TYPE, shipment.getId().toString(),
                NotificationSendEvent.builder()
                        .userId(order.getUserId())
                        .recipient(order.getUserEmail())
                        .templateCode("SHIPMENT_" + shipment.getStatus().name())
                        .subject(subjectFor(shipment))
                        .params(params)
                        .referenceId(order.getId())
                        .build());
    }

    /**
     * The tracking sentence, or nothing.
     *
     * <p>Empty rather than "Tracking number: none" when there is not one. A message with a
     * placeholder where a number should be reads as a bug to the person receiving it, and they
     * are right.
     */
    private static String trackingLine(Shipment shipment) {
        if (shipment.getTrackingNumber() == null) {
            return "";
        }
        String line = "Tracking number: " + shipment.getTrackingNumber();
        return shipment.getTrackingUrl() == null
                ? line
                : line + System.lineSeparator() + shipment.getTrackingUrl();
    }

    private static String subjectFor(Shipment shipment) {
        return switch (shipment.getStatus()) {
            case DISPATCHED -> "Your order " + shipment.getOrderNumber() + " is on its way";
            case ATTEMPTED -> "We could not deliver order " + shipment.getOrderNumber();
            case DELIVERED -> "Your order " + shipment.getOrderNumber() + " has arrived";
            case RETURNED -> "Order " + shipment.getOrderNumber() + " has come back to us";
            default -> "Update on order " + shipment.getOrderNumber();
        };
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Guard for callers that reach this service without an authenticated principal. */
    static AuthenticatedUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return caller;
    }
}
