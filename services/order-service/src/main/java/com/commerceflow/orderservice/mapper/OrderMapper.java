package com.commerceflow.orderservice.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.commerceflow.common.event.OrderLineItem;
import com.commerceflow.orderservice.dto.DeliveryDestination;
import com.commerceflow.orderservice.dto.DeliverySummary;
import com.commerceflow.orderservice.dto.OrderItemResponse;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderItem;
import com.commerceflow.orderservice.entity.OrderView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Translations between the write model, the read model, the API and the saga events.
 *
 * <p>Hand written on purpose: the read model stores its line items as JSON, so the mapping is not
 * a field-by-field copy that a generator could infer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMapper {

    private static final TypeReference<List<OrderItemResponse>> ITEM_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /** Write model line to the API/read-model shape. */
    public OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getSku(),
                item.getProductName(),
                item.getProductCategory(),
                item.getProductImageUrl(),
                item.getQuantity(),
                item.getListPrice(),
                item.getDiscountAmount(),
                item.getUnitPrice(),
                item.getSubtotal(),
                item.getTaxName(),
                item.getTaxRate(),
                item.getTaxAmount());
    }

    /** Write model line to the shape carried on {@code order.created}. */
    public OrderLineItem toEventLine(OrderItem item) {
        return OrderLineItem.builder()
                .productId(item.getProductId())
                .sku(item.getSku())
                .productName(item.getProductName())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .build();
    }

    public List<OrderLineItem> toEventLines(Order order) {
        return order.getItems().stream().map(this::toEventLine).toList();
    }

    /** Direct projection of the aggregate, used by the command side before the read model exists. */
    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getUserEmail(),
                order.getStatus().name(),
                order.getSubtotalAmount(),
                order.getDiscountTotal(),
                order.getTaxTotal(),
                order.getShippingAmount(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getShippingAddress(),
                new DeliveryDestination(order.getRecipientName(), order.getShippingPhone(),
                        order.getShippingLine1(), order.getShippingLine2(),
                        order.getShippingCity(), order.getShippingRegion(),
                        order.getShippingPostalCode(), order.getShippingCountry()),
                new DeliverySummary(name(order.getShippingMethod()), order.getDeliveryMinDays(),
                        order.getDeliveryMaxDays()),
                order.itemCount(),
                order.getItems().stream().map(this::toItemResponse).toList(),
                order.getPaymentId(),
                order.getCouponCode(),
                order.getFailedStep(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getCompletedAt());
    }

    /** The query side: a single flat row plus its denormalised item JSON. */
    public OrderResponse toResponse(OrderView view) {
        return new OrderResponse(
                view.getOrderId(),
                view.getOrderNumber(),
                view.getUserId(),
                view.getUserEmail(),
                view.getStatus().name(),
                view.getSubtotalAmount(),
                view.getDiscountTotal(),
                view.getTaxTotal(),
                view.getShippingAmount(),
                view.getTotalAmount(),
                view.getCurrency(),
                view.getShippingAddress(),
                new DeliveryDestination(view.getRecipientName(), view.getShippingPhone(),
                        view.getShippingLine1(), view.getShippingLine2(),
                        view.getShippingCity(), view.getShippingRegion(),
                        view.getShippingPostalCode(), view.getShippingCountry()),
                new DeliverySummary(name(view.getShippingMethod()), view.getDeliveryMinDays(),
                        view.getDeliveryMaxDays()),
                view.getItemCount(),
                readItems(view),
                view.getPaymentId(),
                view.getCouponCode(),
                view.getFailedStep(),
                view.getFailureReason(),
                view.getCreatedAt(),
                view.getUpdatedAt(),
                view.getCompletedAt());
    }

    private static String name(com.commerceflow.orderservice.pricing.ShippingMethod method) {
        return method == null ? null : method.name();
    }

    /** Serialises the line items for the read model column. */
    public String writeItems(Order order) {
        try {
            return objectMapper.writeValueAsString(
                    order.getItems().stream().map(this::toItemResponse).toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to project the items of order " + order.getId(), ex);
        }
    }

    /**
     * Reads the line items back out of the read model.
     *
     * <p>A projection that cannot be parsed degrades to an empty item list rather than failing the
     * whole request: the customer still sees the order, its number, status and total.
     */
    private List<OrderItemResponse> readItems(OrderView view) {
        if (view.getItemsJson() == null || view.getItemsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(view.getItemsJson(), ITEM_LIST);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.error("Corrupt read-model item JSON for order {}", view.getOrderId(), ex);
            return List.of();
        }
    }
}
