package com.commerceflow.orderservice.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.event.NotificationSendEvent;
import com.commerceflow.common.event.RefundReturnCommand;
import com.commerceflow.common.event.RestockReturnedGoodsCommand;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.returns.ReturnDtos.CreateReturnRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnItemRequest;
import com.commerceflow.orderservice.returns.ReturnDtos.ReturnResponse;

/**
 * Sending goods back.
 *
 * <p>Most of what follows is arithmetic, because that is where a return costs real money when it
 * is wrong: refund a cent too much on every order and nobody notices for a year. The rest pins the
 * two rules that stop the same goods being paid for twice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReturnServiceTest {

    private static final String EUR = "EUR";

    @Mock
    private OrderRepository orders;

    @Mock
    private ReturnRequestRepository returns;

    @Mock
    private OutboxService outboxService;

    @Mock
    private AuditService auditService;

    private ReturnService service;

    private Order order;
    private OrderItem laptop;
    private OrderItem mouse;
    private AuthenticatedUser customer;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        service = new ReturnService(orders, returns, outboxService, auditService);

        UUID userId = UUID.randomUUID();

        // 21% VAT, which is what the Netherlands charges and what the seeded rate card uses.
        laptop = OrderItem.builder()
                .id(UUID.randomUUID()).productId(UUID.randomUUID())
                .sku("LAPTOP-14").productName("Laptop 14")
                .quantity(2)
                .listPrice(new BigDecimal("100.00"))
                .discountAmount(BigDecimal.ZERO)
                .unitPrice(new BigDecimal("100.00"))
                .subtotal(new BigDecimal("200.00"))
                .taxRate(new BigDecimal("0.2100"))
                .taxAmount(new BigDecimal("42.00"))
                .build();

        mouse = OrderItem.builder()
                .id(UUID.randomUUID()).productId(UUID.randomUUID())
                .sku("MOUSE").productName("Mouse")
                .quantity(1)
                .listPrice(new BigDecimal("50.00"))
                .discountAmount(BigDecimal.ZERO)
                .unitPrice(new BigDecimal("50.00"))
                .subtotal(new BigDecimal("50.00"))
                .taxRate(new BigDecimal("0.2100"))
                .taxAmount(new BigDecimal("10.50"))
                .build();

        order = Order.builder()
                .id(UUID.randomUUID()).orderNumber("ORD-1").userId(userId)
                .userEmail("ada@commerceflow.io")
                .status(OrderStatus.COMPLETED)
                .currency(EUR)
                .shippingAmount(new BigDecimal("6.95"))
                .totalAmount(new BigDecimal("309.45"))
                .items(new ArrayList<>())
                .build();
        order.getItems().add(laptop);
        order.getItems().add(mouse);

        customer = new AuthenticatedUser(userId, "ada@commerceflow.io", Set.of("CUSTOMER"),
                UUID.randomUUID().toString());
        admin = new AuthenticatedUser(UUID.randomUUID(), "ops@commerceflow.io", Set.of("ADMIN"),
                UUID.randomUUID().toString());

        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(returns.claimedQuantities(order.getId())).thenReturn(Map.of());
        when(returns.save(any(ReturnRequest.class))).thenAnswer(call -> call.getArgument(0));
    }

    // =============================================================================== the money

    @Test
    @DisplayName("one unit gives back what was charged for it, plus its tax")
    void refundIsNetPlusTax() {
        ReturnResponse response = service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(laptop.getId(), 1)),
                        "Changed my mind"),
                customer);

        // 100.00 net + 21.00 tax. Not half of the line's 42.00 tax by coincidence — it is the
        // rate applied to the returned amount, which is what makes a partial return explainable.
        assertThat(response.refundAmount()).isEqualByComparingTo("121.00");
    }

    @Test
    @DisplayName("returning a whole line gives back exactly what the line was charged")
    void wholeLineReproducesTheOriginalTax() {
        ReturnResponse response = service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(laptop.getId(), 2)),
                        "Faulty"),
                customer);

        // 200.00 + 42.00, which is the line's own subtotal and taxAmount. The same calculation
        // that produced them, so it has to agree — if it ever does not, one of the two is wrong.
        assertThat(response.refundAmount()).isEqualByComparingTo("242.00");
    }

    @Test
    @DisplayName("a discounted line refunds what was paid, not the shelf price")
    void couponIsRespected() {
        // A coupon is allocated onto the line: listPrice stays, unitPrice drops. Refunding
        // listPrice would hand back money the customer never paid — the shop would fund the
        // discount twice, once at checkout and once on return.
        OrderItem discounted = OrderItem.builder()
                .id(UUID.randomUUID()).productId(UUID.randomUUID())
                .sku("SALE").productName("Discounted thing")
                .quantity(1)
                .listPrice(new BigDecimal("100.00"))
                .discountAmount(new BigDecimal("10.00"))
                .unitPrice(new BigDecimal("90.00"))
                .subtotal(new BigDecimal("90.00"))
                .taxRate(new BigDecimal("0.2100"))
                .taxAmount(new BigDecimal("18.90"))
                .build();
        order.getItems().clear();
        order.getItems().add(discounted);

        ReturnResponse response = service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(discounted.getId(), 1)),
                        "Changed my mind"),
                customer);

        // 90.00 + 18.90 + 6.95 delivery, because this is now the whole order.
        assertThat(response.refundAmount()).isEqualByComparingTo("115.85");
    }

    @Test
    @DisplayName("returning the whole order gives the delivery charge back too")
    void wholeOrderRefundsShipping() {
        ReturnResponse response = service.request(order.getId(),
                new CreateReturnRequest(List.of(
                        new ReturnItemRequest(laptop.getId(), 2),
                        new ReturnItemRequest(mouse.getId(), 1)),
                        "None of it fits"),
                customer);

        // 242.00 + 60.50 + 6.95
        assertThat(response.refundShipping()).isTrue();
        assertThat(response.refundAmount()).isEqualByComparingTo("309.45");
    }

    @Test
    @DisplayName("keeping one item means keeping the delivery charge")
    void partialReturnDoesNotRefundShipping() {
        ReturnResponse response = service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(mouse.getId(), 1)),
                        "Wrong colour"),
                customer);

        // The parcel was still delivered. 50.00 + 10.50 and nothing else.
        assertThat(response.refundShipping()).isFalse();
        assertThat(response.refundAmount()).isEqualByComparingTo("60.50");
    }

    // =========================================================== nothing comes back twice

    @Test
    @DisplayName("more than was ordered cannot be sent back")
    void cannotReturnMoreThanWasBought() {
        assertThatThrownBy(() -> service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(laptop.getId(), 3)),
                        "Greedy"),
                customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("at most 2");
    }

    @Test
    @DisplayName("what an earlier return already claimed does not come back again")
    void earlierClaimsCount() {
        when(returns.claimedQuantities(order.getId())).thenReturn(Map.of(laptop.getId(), 1));

        assertThatThrownBy(() -> service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(laptop.getId(), 2)),
                        "The other one too"),
                customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already returned 1");
    }

    @Test
    @DisplayName("what is left of each line is offered before the customer chooses")
    void returnableShowsWhatIsLeft() {
        when(returns.claimedQuantities(order.getId())).thenReturn(Map.of(laptop.getId(), 1));

        var lines = service.returnable(order.getId(), customer);

        // Showing the answer beats asking somebody to guess and then refusing them.
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.orderItemId()).isEqualTo(laptop.getId());
            assertThat(line.orderedQuantity()).isEqualTo(2);
            assertThat(line.alreadyReturned()).isEqualTo(1);
            assertThat(line.returnableQuantity()).isEqualTo(1);
            assertThat(line.refundPerUnit()).isEqualByComparingTo("121.00");
        });
    }

    @Test
    @DisplayName("a line from somebody else's order is not returnable against this one")
    void unknownLineIsRefused() {
        assertThatThrownBy(() -> service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(UUID.randomUUID(), 1)),
                        "Not mine"),
                customer))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ================================================================== who and when

    @Test
    @DisplayName("an order that has not completed is cancelled, not returned")
    void onlyCompletedOrders() {
        order.setStatus(OrderStatus.PAID);

        assertThatThrownBy(() -> service.request(order.getId(),
                new CreateReturnRequest(List.of(new ReturnItemRequest(mouse.getId(), 1)), "Nope"),
                customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled rather than returned");
    }

    @Test
    @DisplayName("somebody else's order is invisible rather than forbidden")
    void otherPeoplesOrdersAreInvisible() {
        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID(), "eve@example.com",
                Set.of("CUSTOMER"), UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.returnable(order.getId(), stranger))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a customer may call it off only before anybody has decided")
    void cancelOnlyBeforeADecision() {
        ReturnRequest request = pending(ReturnStatus.APPROVED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        // Once approved the goods are in the post, and cancelling would leave a parcel arriving
        // that nothing expects.
        assertThatThrownBy(() -> service.cancel(request.getId(), customer))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer be called off");
    }

    // ===================================================================== the refund

    @Test
    @DisplayName("refunding asks Payment Service and waits for the answer")
    void refundGoesOutOnTheOutbox() {
        ReturnRequest request = pending(ReturnStatus.RECEIVED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.refund(request.getId(), admin);

        ArgumentCaptor<RefundReturnCommand> command =
                ArgumentCaptor.forClass(RefundReturnCommand.class);
        verify(outboxService).append(anyString(), anyString(), command.capture());

        // The command and the state change are one transaction, so the two cannot disagree about
        // whether a refund was asked for.
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(command.getValue().getReturnId()).isEqualTo(request.getId());
        assertThat(command.getValue().getAmount()).isEqualByComparingTo("60.50");
    }

    @Test
    @DisplayName("pressing refund twice does not ask twice")
    void refundIsNotIssuedTwice() {
        ReturnRequest request = pending(ReturnStatus.REFUND_PENDING);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.refund(request.getId(), admin);

        // Two people working the same queue, or one impatient click. Neither may send the money
        // a second time.
        verify(outboxService, never()).append(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("only goods that are actually back can be refunded")
    void refundNeedsTheGoodsBack() {
        ReturnRequest request = pending(ReturnStatus.APPROVED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.refund(request.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("RECEIVED");
    }

    @Test
    @DisplayName("approving is recorded against the operator who did it")
    void decisionsAreAudited() {
        ReturnRequest request = pending(ReturnStatus.REQUESTED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.approve(request.getId(), "Send it back to the Amsterdam depot", admin);

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo(admin.userId());
        verify(auditService).record(eq(admin), eq("RETURN_APPROVED"), eq("RETURN"),
                eq(request.getId()), anyString());
    }

    @Test
    @DisplayName("a return can only be decided once")
    void decisionsAreNotRepeatable() {
        ReturnRequest request = pending(ReturnStatus.APPROVED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reject(request.getId(), "On second thoughts", admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("REQUESTED");
    }

    // ============================================================ back on the shelf

    @Test
    @DisplayName("goods that can be resold are sent back to inventory")
    void resellableGoodsAreRestocked() {
        ReturnRequest request = pending(ReturnStatus.APPROVED);
        request.addItem(ReturnRequestItem.builder()
                .id(UUID.randomUUID()).orderItemId(mouse.getId()).productId(mouse.getProductId())
                .productName("Mouse").sku("MOUSE").quantity(1)
                .refundAmount(new BigDecimal("60.50")).build());
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.markReceived(request.getId(), true, admin);

        ArgumentCaptor<RestockReturnedGoodsCommand> command =
                ArgumentCaptor.forClass(RestockReturnedGoodsCommand.class);
        verify(outboxService).append(anyString(), anyString(), command.capture());

        // Explicit lines, not "the whole order". Inventory has a command that restocks an entire
        // reservation and it is for cancellation — using it here would put back the items the
        // customer kept.
        assertThat(request.getRestocked()).isTrue();
        assertThat(command.getValue().getLines()).singleElement().satisfies(line -> {
            assertThat(line.getProductId()).isEqualTo(mouse.getProductId());
            assertThat(line.getQuantity()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("goods written off are recorded and never sent back to inventory")
    void writtenOffGoodsAreNotRestocked() {
        ReturnRequest request = pending(ReturnStatus.APPROVED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.markReceived(request.getId(), false, admin);

        // A cracked screen comes back to the warehouse and never to a customer. Restocking it
        // would put a broken thing in front of the next person who orders one.
        assertThat(request.getRestocked()).isFalse();
        verify(outboxService, never()).append(anyString(), anyString(),
                any(RestockReturnedGoodsCommand.class));
    }

    @Test
    @DisplayName("a return nobody has opened is not the same as one written off")
    void notReceivedIsDistinctFromWrittenOff() {
        ReturnRequest untouched = pending(ReturnStatus.REQUESTED);

        // Null, not false. Collapsing the two would make "we have not looked at it" read as
        // "we looked and threw it away".
        assertThat(untouched.getRestocked()).isNull();
    }

    // ============================================================ telling the customer

    @Test
    @DisplayName("approving tells the customer, because they have to post something back")
    void approvalIsEmailed() {
        ReturnRequest request = pending(ReturnStatus.REQUESTED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.approve(request.getId(), "Send it to the Amsterdam depot", admin);

        ArgumentCaptor<NotificationSendEvent> notice =
                ArgumentCaptor.forClass(NotificationSendEvent.class);
        verify(outboxService).append(anyString(), anyString(), notice.capture());

        // An approval the customer never hears about is useless to them: they have been told to
        // send something back and do not know where to.
        assertThat(notice.getValue().getTemplateCode()).isEqualTo("RETURN_APPROVED");
        assertThat(notice.getValue().getRecipient()).isEqualTo("ada@commerceflow.io");
        assertThat(notice.getValue().getParams()).containsEntry("note",
                "Send it to the Amsterdam depot");
    }

    @Test
    @DisplayName("refusing tells the customer too, and carries the reason")
    void refusalIsEmailed() {
        ReturnRequest request = pending(ReturnStatus.REQUESTED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.reject(request.getId(), "Outside the 14 day window", admin);

        ArgumentCaptor<NotificationSendEvent> notice =
                ArgumentCaptor.forClass(NotificationSendEvent.class);
        verify(outboxService).append(anyString(), anyString(), notice.capture());

        // Silence reads as being ignored, and the note is the only explanation they get.
        assertThat(notice.getValue().getTemplateCode()).isEqualTo("RETURN_REJECTED");
        assertThat(notice.getValue().getParams())
                .containsEntry("note", "Outside the 14 day window");
    }

    @Test
    @DisplayName("a decision with no note does not send the customer the word null")
    void absentNoteRendersEmpty() {
        ReturnRequest request = pending(ReturnStatus.REQUESTED);
        when(returns.findById(request.getId())).thenReturn(Optional.of(request));

        service.approve(request.getId(), null, admin);

        ArgumentCaptor<NotificationSendEvent> notice =
                ArgumentCaptor.forClass(NotificationSendEvent.class);
        verify(outboxService).append(anyString(), anyString(), notice.capture());

        // A template rendering "null" at a customer reads as a bug, and they are right.
        assertThat(notice.getValue().getParams()).containsEntry("note", "");
    }

    private ReturnRequest pending(ReturnStatus status) {
        return ReturnRequest.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .userEmail(order.getUserEmail())
                .status(status)
                .reason("Wrong colour")
                .refundAmount(new BigDecimal("60.50"))
                .currency(EUR)
                .requestedAt(Instant.now())
                .items(new ArrayList<>())
                .build();
    }
}
