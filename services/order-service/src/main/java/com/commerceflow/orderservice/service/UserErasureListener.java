package com.commerceflow.orderservice.service;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.UserErasedEvent;
import com.commerceflow.common.idempotency.IdempotencyService;
import com.commerceflow.orderservice.cart.CartRepository;
import com.commerceflow.orderservice.cart.WishlistRepository;
import com.commerceflow.orderservice.repository.OrderRepository;
import com.commerceflow.orderservice.repository.OrderViewRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scrubs a customer from the orders they placed, without losing the orders.
 *
 * <h2>Two different treatments, and the line between them</h2>
 *
 * <p><b>Anonymised:</b> the order and its read model. An order is a financial record — it has a
 * total, a tax figure and a payment behind it, and an accountant needs all of that to still add up
 * next year. What must not survive is the customer inside it: the email, the recipient name, the
 * street it went to.
 *
 * <p><b>Deleted:</b> the basket and the wishlist. Those are only ever personal — nothing
 * references them, no report counts them, and keeping an anonymised shopping list would be keeping
 * something for no reason at all.
 *
 * <h2>The country stays</h2>
 *
 * <p>{@code shippingCountry} is not scrubbed, and that is deliberate. It is what chose the tax
 * rate, so an invoice that no longer says which country it was for cannot be explained — and a
 * country on its own does not identify anybody.
 *
 * <p>The postcode <em>does</em> go. Combined with almost anything else it is close to an
 * identifier, and no report needs it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.USER_ERASED,
        groupId = UserErasureListener.CONSUMER_GROUP,
        id = "order-user-erasure")
public class UserErasureListener {

    static final String CONSUMER_GROUP = "order-service-erasure";

    private final OrderRepository orders;
    private final OrderViewRepository orderViews;
    private final CartRepository carts;
    private final WishlistRepository wishlist;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    @KafkaHandler
    @Transactional
    public void onUserErased(UserErasedEvent event) {
        UUID userId = event.getUserId();
        if (userId == null) {
            return;
        }
        if (!idempotencyService.claim(CONSUMER_GROUP, event.getEventId(), event.getEventType())) {
            return;
        }

        int scrubbed = 0;
        for (var order : orders.findByUserId(userId)) {
            order.anonymise(event.getPlaceholderEmail(), event.getPlaceholderName());
            orders.save(order);
            scrubbed++;
        }

        // The read model is a projection, but it holds its own copy of the same fields — so it
        // needs scrubbing too rather than being left to catch up. Nothing re-projects an old
        // order on its own.
        orderViews.findByUserId(userId).forEach(view -> {
            view.setUserEmail(event.getPlaceholderEmail());
            view.setRecipientName(event.getPlaceholderName());
            view.setShippingAddress(event.getPlaceholderName());
            view.setShippingLine1(null);
            view.setShippingLine2(null);
            view.setShippingPostalCode(null);
            view.setShippingPhone(null);
            orderViews.save(view);
        });

        carts.findByUserId(userId).ifPresent(carts::delete);
        wishlist.findByUserIdOrderByAddedAtDesc(userId).forEach(wishlist::delete);

        // Recorded as a system action: nobody in this service did it, an event arrived. The
        // entry is what proves the erasure reached here, which is the question an audit asks.
        auditService.recordSystem("USER_ERASED", "USER", userId,
                "Anonymised " + scrubbed + " order(s); removed the basket and wishlist");

        log.warn("Erased customer {} from {} order(s). The orders themselves are kept.",
                userId, scrubbed);
    }

    /**
     * Anything else on this topic.
     *
     * <p>Present so an unexpected payload is ignored rather than stopping the partition. An
     * erasure that has to be retried is recoverable; a poisoned partition that blocks every
     * later erasure is not.
     */
    @KafkaHandler(isDefault = true)
    public void onOther(Object payload) {
        log.debug("Ignoring {} on {}", payload.getClass().getSimpleName(), KafkaTopics.USER_ERASED);
    }
}
