package com.commerceflow.inventoryservice.review;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.audit.AuditService;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.UserErasedEvent;
import com.commerceflow.common.idempotency.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scrubs a customer from the reviews they wrote, without removing the reviews.
 *
 * <h2>The words stay; the author goes</h2>
 *
 * <p>A published review is part of a product page other customers are reading and part of the
 * rating other customers are relying on. Deleting it because its author closed their account would
 * silently change the shop's ratings and remove opinions the people who read them still hold.
 *
 * <p>So the text survives and the byline becomes a placeholder. The {@code user_id} stays too —
 * without it the unique constraint that stops one person writing twice would no longer apply to
 * these rows, and it identifies nobody on its own once the account it points at is anonymous.
 *
 * <h2>Purchase history goes entirely</h2>
 *
 * <p>{@code verified_purchases} exists only to decide whether somebody may claim a badge. It is a
 * record of what a named person bought, nothing depends on it after the fact, and there is no
 * reason to keep it. The badge already on an existing review is left alone: it was true when it
 * was written, and that is what it says.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(
        topics = KafkaTopics.USER_ERASED,
        groupId = UserErasureListener.CONSUMER_GROUP,
        id = "inventory-user-erasure")
public class UserErasureListener {

    static final String CONSUMER_GROUP = "inventory-service-erasure";

    private final ReviewRepository reviews;
    private final VerifiedPurchaseRepository purchases;
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
        for (Review review : reviews.findByUserIdOrderByCreatedAtDesc(userId)) {
            review.setAuthorName(event.getPlaceholderName());
            reviews.save(review);
            scrubbed++;
        }

        int purchasesRemoved = purchases.deleteByUserId(userId);

        auditService.recordSystem("USER_ERASED", "USER", userId,
                "Anonymised " + scrubbed + " review(s); removed " + purchasesRemoved
                        + " purchase record(s)");

        log.warn("Erased customer {} from {} review(s). The reviews themselves are kept.",
                userId, scrubbed);
    }

    /** Anything else on this topic: ignored rather than allowed to stop the partition. */
    @KafkaHandler(isDefault = true)
    public void onOther(Object payload) {
        log.debug("Ignoring {} on {}", payload.getClass().getSimpleName(), KafkaTopics.USER_ERASED);
    }
}
