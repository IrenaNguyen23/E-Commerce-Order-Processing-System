package com.commerceflow.orderservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.address.PostalAddress;
import com.commerceflow.common.event.OrderCreatedEvent;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.outbox.OutboxService;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.config.OrderProperties;
import com.commerceflow.orderservice.cart.CartService;
import com.commerceflow.orderservice.dto.CatalogProduct;
import com.commerceflow.orderservice.dto.CreateOrderRequest;
import com.commerceflow.orderservice.dto.OrderItemRequest;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.pricing.OrderPricingService;
import com.commerceflow.orderservice.pricing.ShippingQuote;
import com.commerceflow.orderservice.promotion.AppliedCoupon;
import com.commerceflow.orderservice.promotion.CouponService;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.saga.OrderSagaOrchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The command side of Order Service and the entry point of the order saga.
 *
 * <p>Placing an order writes five things in one transaction — the aggregate, its read-model
 * projection, the saga instance, the first saga command and the {@code order.created} domain
 * event. Either the customer has an order and the orchestrator is driving it, or neither
 * happened; there is no state in which an order exists with nothing to advance it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCommandService {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final OrderRepository orderRepository;
    private final OrderNumberGenerator orderNumberGenerator;
    private final ProductCatalogClient catalogClient;
    private final OrderProjectionService projectionService;
    private final OrderPricingService pricingService;
    private final CouponService couponService;
    private final CartService cartService;
    private final OrderSagaOrchestrator orchestrator;
    private final OutboxService outboxService;
    private final OrderMapper orderMapper;
    private final OrderProperties properties;

    /**
     * Places a new order.
     *
     * @param customer the verified caller; the order is always placed for the caller, never for an
     *     arbitrary user id supplied in the body
     * @throws BusinessException when the basket is empty, too large, references an unknown or
     *     inactive product, or mixes currencies
     */
    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request, AuthenticatedUser customer) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.EMPTY_ORDER);
        }
        if (request.items().size() > properties.getMaxItemsPerOrder()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "An order may not contain more than " + properties.getMaxItemsPerOrder() + " lines");
        }

        Order replay = findReplay(request, customer);
        if (replay != null) {
            log.info("Idempotent replay of order {} for customer {}", replay.getId(),
                    customer.userId());
            return orderMapper.toResponse(replay);
        }

        Map<UUID, CatalogProduct> catalogue = catalogClient.lookup(
                request.items().stream().map(OrderItemRequest::productId).toList());

        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();
        Order order = Order.builder()
                .id(orderId)
                .orderNumber(orderNumberGenerator.next())
                .userId(customer.userId())
                .userEmail(customer.email())
                .status(OrderStatus.CREATED)
                .shippingAddress(request.shippingAddress().formatted())
                .idempotencyKey(blankToNull(request.idempotencyKey()))
                .subtotalAmount(BigDecimal.ZERO)
                .discountTotal(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .currency(null)
                .createdAt(now)
                .updatedAt(now)
                .items(new ArrayList<>())
                .build();

        applyDestination(order, request.shippingAddress());

        String currency = null;
        for (OrderItemRequest line : request.items()) {
            CatalogProduct product = catalogue.get(line.productId());
            if (product == null) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Unknown product " + line.productId());
            }
            if (!product.active()) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "Product " + product.sku() + " is no longer for sale");
            }
            if (currency == null) {
                currency = product.currency();
            } else if (!currency.equals(product.currency())) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE,
                        "An order cannot mix currencies: " + currency + " and " + product.currency());
            }

            // The snapshot. Everything the order will ever need to describe itself is copied
            // here and never read from the catalogue again — an order is a historical record,
            // and a later rename, re-shot photo or price change must not rewrite what the
            // customer saw and agreed to pay.
            OrderItem item = OrderItem.builder()
                    .id(UUID.randomUUID())
                    .productId(product.id())
                    .sku(product.sku())
                    .productName(product.name())
                    .productCategory(product.category())
                    .productCategorySlug(product.categorySlug())
                    .productImageUrl(product.imageUrl())
                    .quantity(line.quantity())
                    .listPrice(product.price())
                    // No promotion engine yet, so nothing comes off. The field exists so that
                    // when one does, the reduction is recorded next to the price it applied to.
                    .discountAmount(BigDecimal.ZERO)
                    .build();
            item.recalculateSubtotal();
            order.addItem(item);
        }

        order.setCurrency(currency);

        // The coupon comes first, before anything is priced. It changes the taxable base and the
        // free-delivery threshold, so applying it afterwards would tax money nobody paid and
        // measure the threshold against a basket the customer is not buying.
        AppliedCoupon coupon = claimCoupon(request.couponCode(), order);

        // Delivery, then tax, then the total. The sequence matters and lives in one place; see
        // OrderPricingService for why getting it wrong produces a wrong invoice rather than an
        // error.
        ShippingQuote delivery = pricingService.price(order, request.shippingMethod(),
                coupon != null && coupon.freesShipping());

        Order saved = orderRepository.save(order);
        projectionService.project(saved);

        // Announced, not depended on. No saga participant subscribes to order.created any more —
        // the orchestrator issues the first command directly below. This stays published because
        // it is the documented public contract and what external consumers read.
        outboxService.append(AGGREGATE_TYPE, orderId.toString(), OrderCreatedEvent.builder()
                .orderId(saved.getId())
                .orderNumber(saved.getOrderNumber())
                .userId(saved.getUserId())
                .userEmail(saved.getUserEmail())
                .totalAmount(saved.getTotalAmount())
                .currency(saved.getCurrency())
                .items(orderMapper.toEventLines(saved))
                .build());

        // Same transaction as the order itself: an order that exists is an order being driven.
        orchestrator.start(saved);

        // And the basket goes, in the same transaction. A customer who lands back on a basket
        // page still showing what they just bought will buy it again.
        cartService.emptyAfterCheckout(customer.userId());

        log.info("Placed order {} ({}) for customer {}: {} {} across {} line(s), {} to {} ({})",
                saved.getOrderNumber(), saved.getId(), customer.userId(), saved.getTotalAmount(),
                saved.getCurrency(), saved.getItems().size(), delivery.method(),
                saved.getShippingCountry(), delivery.description());

        return orderMapper.toResponse(saved);
    }

    /**
     * Claims a discount code, if one was supplied.
     *
     * <p>Returns {@code null} when there was no code. A code that cannot be used throws, and
     * that is deliberate: silently ignoring an unusable code would place the order at full price
     * without saying so, and the customer finds out from their statement.
     */
    private AppliedCoupon claimCoupon(String code, Order order) {
        if (code == null || code.isBlank()) {
            return null;
        }
        AppliedCoupon applied = couponService.apply(code, order);
        order.setCouponCode(applied.code());
        return applied;
    }

    /**
     * Copies the destination onto the order.
     *
     * <p>A copy, never a reference. The country code in particular is kept because it is what
     * chose the tax rate and the delivery charge — an invoice has to be explainable years later,
     * without asking where the customer lives now.
     */
    private static void applyDestination(Order order, PostalAddress address) {
        order.setRecipientName(address.recipientName());
        order.setShippingPhone(address.phone());
        order.setShippingLine1(address.line1());
        order.setShippingLine2(address.line2());
        order.setShippingCity(address.city());
        order.setShippingRegion(address.region());
        order.setShippingPostalCode(address.postalCode());
        order.setShippingCountry(address.country());
    }

    /**
     * Returns the original order when the caller replays a create request with the same
     * idempotency key, so a retry over a flaky connection cannot produce two orders.
     */
    private Order findReplay(CreateOrderRequest request, AuthenticatedUser customer) {
        String key = blankToNull(request.idempotencyKey());
        if (key == null) {
            return null;
        }
        return orderRepository.findByUserIdAndIdempotencyKey(customer.userId(), key).orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
