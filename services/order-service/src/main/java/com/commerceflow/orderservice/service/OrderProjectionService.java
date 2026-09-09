package com.commerceflow.orderservice.service;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.orderservice.entity.Order;
import com.commerceflow.orderservice.entity.OrderView;
import com.commerceflow.orderservice.mapper.OrderMapper;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.repository.OrderViewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the CQRS read model in step with the aggregate.
 *
 * <p>{@link Propagation#MANDATORY}: the projection is written in the same transaction as the
 * state change it reflects. That is what makes this read model strongly consistent rather than
 * eventually consistent, without giving up any of the read-side benefits — one flat row, no
 * joins, no lazy collections.
 *
 * <p>Because the projection is derived, it can also be rebuilt from the aggregate at any time;
 * {@link #rebuild(UUID)} exists for exactly that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProjectionService {

    private final OrderViewRepository orderViewRepository;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    /** Creates or refreshes the projection of {@code order}. */
    @Transactional(propagation = Propagation.MANDATORY)
    @CacheEvict(cacheNames = OrderQueryService.CACHE_ORDER, key = "#order.id")
    public OrderView project(Order order) {
        OrderView view = orderViewRepository.findById(order.getId())
                .orElseGet(() -> OrderView.builder().orderId(order.getId()).build());

        view.setOrderNumber(order.getOrderNumber());
        view.setUserId(order.getUserId());
        view.setUserEmail(order.getUserEmail());
        view.setStatus(order.getStatus());
        view.setSubtotalAmount(order.getSubtotalAmount());
        view.setDiscountTotal(order.getDiscountTotal());
        view.setTaxTotal(order.getTaxTotal());
        view.setShippingAmount(order.getShippingAmount());
        view.setTotalAmount(order.getTotalAmount());
        view.setCurrency(order.getCurrency());
        view.setShippingAddress(order.getShippingAddress());
        view.setRecipientName(order.getRecipientName());
        view.setShippingPhone(order.getShippingPhone());
        view.setShippingLine1(order.getShippingLine1());
        view.setShippingLine2(order.getShippingLine2());
        view.setShippingCity(order.getShippingCity());
        view.setShippingRegion(order.getShippingRegion());
        view.setShippingPostalCode(order.getShippingPostalCode());
        view.setShippingCountry(order.getShippingCountry());
        view.setShippingMethod(order.getShippingMethod());
        view.setDeliveryMinDays(order.getDeliveryMinDays());
        view.setDeliveryMaxDays(order.getDeliveryMaxDays());
        view.setItemCount(order.itemCount());
        view.setItemsJson(orderMapper.writeItems(order));
        view.setPaymentId(order.getPaymentId());
        view.setCouponCode(order.getCouponCode());
        view.setFailureReason(order.getFailureReason());
        view.setFailedStep(order.getFailedStep());
        view.setCreatedAt(order.getCreatedAt());
        view.setUpdatedAt(order.getUpdatedAt());
        view.setCompletedAt(order.getCompletedAt());

        OrderView saved = orderViewRepository.save(view);
        log.debug("Projected order {} as {}", order.getId(), order.getStatus());
        return saved;
    }

    /**
     * Rebuilds one projection from the aggregate.
     *
     * <p>The operational escape hatch for a projection that was lost or corrupted: the write model
     * remains the single source of truth, so nothing is unrecoverable.
     */
    @Transactional
    @CacheEvict(cacheNames = OrderQueryService.CACHE_ORDER, key = "#orderId")
    public boolean rebuild(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    project(order);
                    log.info("Rebuilt the read model for order {}", orderId);
                    return true;
                })
                .orElse(false);
    }
}
