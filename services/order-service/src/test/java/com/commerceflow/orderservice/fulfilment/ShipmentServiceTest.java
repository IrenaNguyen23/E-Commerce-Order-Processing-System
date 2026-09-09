package com.commerceflow.orderservice.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.repository.OrderRepository;

/**
 * Fulfilment.
 *
 * <p>The load-bearing tests here are the two boundaries: a parcel cannot be dispatched against
 * stock that is only reserved, and moving a parcel never moves the order it belongs to. The second
 * is the one that would be easy to "improve" into a bug later.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShipmentServiceTest {

    @Mock
    private ShipmentRepository shipments;

    @Mock
    private OrderRepository orders;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditService auditService;

    private ShipmentService service;
    private Order order;
    private AuthenticatedUser operator;
    private AuthenticatedUser customer;

    @BeforeEach
    void setUp() {
        service = new ShipmentService(shipments, orders, outboxService, auditService);

        UUID customerId = UUID.randomUUID();
        operator = new AuthenticatedUser(UUID.randomUUID(), "ops@commerceflow.io",
                Set.of("ADMIN"), UUID.randomUUID().toString());
        customer = new AuthenticatedUser(customerId, "ada@commerceflow.io",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());

        order = Order.builder()
                .id(UUID.randomUUID()).orderNumber("CF-20260828-000001")
                .userId(customerId).userEmail("ada@commerceflow.io")
                .status(OrderStatus.COMPLETED)
                .subtotalAmount(new BigDecimal("100.00")).discountTotal(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("100.00")).currency("EUR")
                .shippingAddress("Ada Lovelace, Keizersgracht 1, 1015 CJ Amsterdam, NL")
                .deliveryMaxDays(3)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .items(new ArrayList<>()).build();

        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(shipments.findByOrderId(order.getId())).thenReturn(List.of());
        when(shipments.save(any(Shipment.class))).thenAnswer(call -> call.getArgument(0));
    }

    private Shipment shipment(ShipmentStatus status) {
        Shipment shipment = Shipment.builder()
                .id(UUID.randomUUID()).orderId(order.getId())
                .orderNumber(order.getOrderNumber()).userId(order.getUserId())
                .status(status).destination(order.getShippingAddress())
                .carrier("PostNL").trackingNumber("3SABCD1234567")
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .events(new ArrayList<>()).build();
        when(shipments.findById(shipment.getId())).thenReturn(Optional.of(shipment));
        return shipment;
    }

    @Nested
    @DisplayName("Opening a shipment")
    class Opening {

        @Test
        @DisplayName("only for an order the saga has finished with")
        void onlyForCompletedOrders() {
            order.setStatus(OrderStatus.PAID);

            // An order still in flight has stock reserved, not sold. Shipping against a
            // reservation means the warehouse has sent something that may still be refunded.
            assertThatThrownBy(() -> service.create(order.getId(),
                    new CreateShipmentRequest("PostNL", null, null), operator))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("cannot be shipped yet");
        }

        @Test
        @DisplayName("not twice while one is still live")
        void noSecondLiveShipment() {
            Shipment live = shipment(ShipmentStatus.IN_TRANSIT);
            when(shipments.findByOrderId(order.getId())).thenReturn(List.of(live));

            assertThatThrownBy(() -> service.create(order.getId(),
                    new CreateShipmentRequest(null, null, null), operator))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already has a shipment");
        }

        @Test
        @DisplayName("but a finished one does not block a redelivery")
        void finishedShipmentAllowsAnother() {
            Shipment finished = shipment(ShipmentStatus.RETURNED);
            when(shipments.findByOrderId(order.getId())).thenReturn(List.of(finished));

            // A returned parcel is exactly the case where a second shipment is the right answer.
            assertThat(service.create(order.getId(),
                    new CreateShipmentRequest("DHL", null, null), operator).status())
                    .isEqualTo(ShipmentStatus.PENDING);
        }

        @Test
        @DisplayName("carrier and tracking are optional at this point")
        void carrierIsOptional() {
            // A warehouse opens the shipment when it starts picking and learns the tracking
            // number when the parcel is handed over. Requiring it up front means either a lie in
            // the form or no record of the picking step at all.
            assertThat(service.create(order.getId(),
                    new CreateShipmentRequest(null, null, null), operator).carrier()).isNull();
        }

        @Test
        @DisplayName("the promise date comes from the window quoted at checkout")
        void promiseComesFromTheQuote() {
            ShipmentResponse created = service.create(order.getId(),
                    new CreateShipmentRequest("PostNL", null, null), operator);

            // So a late parcel is late against what the customer was actually told, rather than
            // against a target invented after the fact.
            assertThat(created.promisedBy()).isNotNull();
            assertThat(created.late()).isFalse();
        }

        @Test
        @DisplayName("the destination is copied, not referenced")
        void destinationIsCopied() {
            assertThat(service.create(order.getId(),
                    new CreateShipmentRequest(null, null, null), operator).destination())
                    .isEqualTo(order.getShippingAddress());
        }
    }

    @Nested
    @DisplayName("Moving a parcel")
    class Moving {

        @Test
        @DisplayName("a delivered parcel can still be returned")
        void deliveredCanBeReturned() {
            Shipment delivered = shipment(ShipmentStatus.DELIVERED);

            // Looser than the saga's transitions on purpose: this happens to real parcels, and a
            // system that refuses to record it forces warehouse staff to lie to it.
            assertThat(service.advance(delivered.getId(),
                    new UpdateShipmentRequest(ShipmentStatus.RETURNED, "Refused", null, null,
                            null, null), operator).status())
                    .isEqualTo(ShipmentStatus.RETURNED);
        }

        @Test
        @DisplayName("a failed attempt can go back into transit")
        void attemptedCanRetry() {
            Shipment attempted = shipment(ShipmentStatus.ATTEMPTED);

            assertThat(service.advance(attempted.getId(),
                    new UpdateShipmentRequest(ShipmentStatus.IN_TRANSIT, "Retrying", "Amsterdam",
                            null, null, null), operator).status())
                    .isEqualTo(ShipmentStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("nonsense is refused")
        void nonsenseIsRefused() {
            Shipment cancelled = shipment(ShipmentStatus.CANCELLED);

            assertThatThrownBy(() -> service.advance(cancelled.getId(),
                    new UpdateShipmentRequest(ShipmentStatus.DISPATCHED, null, null, null, null,
                            null), operator))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("cannot go from CANCELLED");
        }

        @Test
        @DisplayName("every change appends to the history")
        void historyIsAppendOnly() {
            Shipment dispatched = shipment(ShipmentStatus.DISPATCHED);

            service.advance(dispatched.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.IN_TRANSIT, "Left the depot", "Amsterdam", null, null, null),
                    operator);
            service.advance(dispatched.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.DELIVERED, "Signed for", "Keizersgracht", null, null, null),
                    operator);

            // "When did this ship?" and "the carrier says they tried on Tuesday" are the two
            // questions support gets, and a status column answers neither.
            assertThat(dispatched.getEvents()).hasSize(2);
            assertThat(dispatched.getEvents().get(0).getLocation()).isEqualTo("Amsterdam");
        }

        @Test
        @DisplayName("dispatch and delivery times are set once, not on every correction")
        void timestampsAreSetOnce() {
            Shipment shipment = shipment(ShipmentStatus.DISPATCHED);
            service.advance(shipment.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.DELIVERED, null, null, null, null, null), operator);
            Instant firstDelivery = shipment.getDeliveredAt();

            service.advance(shipment.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.RETURNED, null, null, null, null, null), operator);

            // A parcel re-marked after a failed attempt has not been dispatched twice.
            assertThat(shipment.getDeliveredAt()).isEqualTo(firstDelivery);
        }

        @Test
        @DisplayName("the order's own status is never touched")
        void orderStatusIsLeftAlone() {
            Shipment shipment = shipment(ShipmentStatus.DISPATCHED);

            service.advance(shipment.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.DELIVERED, null, null, null, null, null), operator);

            // The one invariant this whole design exists to hold. Merging the two lifecycles
            // would mean loosening the saga's transition guard, which is what makes redelivery
            // safe in the first place.
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            verify(orders, never()).save(any(Order.class));
        }
    }

    @Nested
    @DisplayName("Telling the customer")
    class Notifying {

        @Test
        @DisplayName("dispatch, failure, delivery and return are worth an email")
        void interestingChangesAreEmailed() {
            Shipment shipment = shipment(ShipmentStatus.PENDING);

            service.advance(shipment.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.DISPATCHED, null, null, null, null, null), operator);

            verify(outboxService).append(anyString(), anyString(),
                    any(NotificationSendEvent.class));
        }

        @Test
        @DisplayName("picking is not")
        void internalStepsAreQuiet() {
            Shipment shipment = shipment(ShipmentStatus.PENDING);

            service.advance(shipment.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.PICKING, null, null, null, null, null), operator);

            // An email per internal state change teaches people to ignore mail from the shop,
            // which costs more than the one they needed to read.
            verify(outboxService, never()).append(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a correction that changes nothing does not email again")
        void repeatedStateIsQuiet() {
            Shipment shipment = shipment(ShipmentStatus.DELIVERED);

            service.advance(shipment.getId(), new UpdateShipmentRequest(
                    ShipmentStatus.DELIVERED, "Corrected the note", null, null, null, null),
                    operator);

            verify(outboxService, never()).append(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("a customer sees their own parcels and not anybody else's")
    void shipmentsAreScopedToTheirOwner() {
        assertThat(service.forOrder(order.getId(), customer)).isEmpty();

        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "eve@example.com",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.forOrder(order.getId(), stranger))
                .hasMessageContaining("Order not found");
    }

    @Test
    @DisplayName("a tracking number is not a password")
    void trackingIsStillScoped() {
        Shipment shipment = shipment(ShipmentStatus.IN_TRANSIT);
        when(shipments.findByTrackingNumber("3SABCD1234567")).thenReturn(Optional.of(shipment));

        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "eve@example.com",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());

        // Tracking numbers are guessable and get forwarded around. Looking one up still has to
        // be scoped to the person asking.
        assertThatThrownBy(() -> service.track("3SABCD1234567", stranger))
                .hasMessageContaining("No shipment");
        assertThat(service.track("3SABCD1234567", customer).trackingNumber())
                .isEqualTo("3SABCD1234567");
    }
}
