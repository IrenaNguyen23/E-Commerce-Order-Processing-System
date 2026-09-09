package com.commerceflow.orderservice.service;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.dto.PageResponse;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.common.security.AuthenticatedUser;
import com.commerceflow.orderservice.dto.OrderResponse;
import com.commerceflow.orderservice.entity.OrderStatus;
import com.commerceflow.orderservice.entity.OrderView;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.repository.OrderViewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The query side of the CQRS split.
 *
 * <p>Reads never touch the {@code orders} aggregate: they are single-row, index-only lookups
 * against the projection, with the line items already denormalised. That keeps the customer-facing
 * "my orders" page cheap no matter how the write model evolves.
 *
 * <p>Authorisation is enforced here rather than in the controller, because it is a property of the
 * data: a customer may only see their own orders, an operator may see all of them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    public static final String CACHE_ORDER = "orderById";

    private static final List<String> SORTABLE_FIELDS =
            List.of("createdAt", "updatedAt", "totalAmount", "status", "orderNumber");

    private final OrderViewRepository orderViewRepository;
    private final OrderMapper orderMapper;

    /**
     * One order, subject to ownership.
     *
     * @throws ResourceNotFoundException when the order does not exist <em>or</em> belongs to
     *     somebody else — a 404 rather than a 403, so the endpoint cannot be used to discover
     *     which order ids exist
     */
    @Transactional(readOnly = true)
    public OrderResponse getById(UUID orderId, AuthenticatedUser caller) {
        OrderView view = orderViewRepository.findById(orderId)
                .filter(candidate -> isVisibleTo(candidate, caller))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId));
        return orderMapper.toResponse(view);
    }

    /** Cached variant used by the internal saga tooling, where no caller identity is involved. */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_ORDER, key = "#orderId")
    public OrderResponse getByIdInternal(UUID orderId) {
        return orderViewRepository.findById(orderId)
                .map(orderMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId));
    }

    /** @throws ResourceNotFoundException when the number is unknown or not the caller's */
    @Transactional(readOnly = true)
    public OrderResponse getByOrderNumber(String orderNumber, AuthenticatedUser caller) {
        OrderView view = orderViewRepository.findByOrderNumber(orderNumber)
                .filter(candidate -> isVisibleTo(candidate, caller))
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderNumber));
        return orderMapper.toResponse(view);
    }

    /**
     * The order list.
     *
     * <p>A customer always gets their own orders; an operator gets every order and may narrow the
     * result to one customer.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> search(AuthenticatedUser caller, String status, UUID userId,
                                              int page, int size, String sortBy, String direction) {
        OrderStatus statusFilter = parseStatus(status);
        Pageable pageable = PageRequest.of(page, size, sort(sortBy, direction));

        Page<OrderView> result = caller.isAdmin()
                ? orderViewRepository.searchAll(userId, statusFilter, pageable)
                : orderViewRepository.searchForUser(caller.userId(), statusFilter, pageable);

        List<OrderResponse> content = result.getContent().stream()
                .map(orderMapper::toResponse)
                .toList();

        return PageResponse.of(content, page, size, result.getTotalElements());
    }

    private static boolean isVisibleTo(OrderView view, AuthenticatedUser caller) {
        return caller.isAdmin() || view.getUserId().equals(caller.userId());
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown order status: " + status);
        }
    }

    private static Sort sort(String sortBy, String direction) {
        String field = SORTABLE_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir =
                "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }
}
