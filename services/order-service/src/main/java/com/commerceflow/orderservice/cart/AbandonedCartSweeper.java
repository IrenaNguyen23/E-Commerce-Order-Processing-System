package com.commerceflow.orderservice.cart;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.orderservice.config.OrderProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes baskets nobody has touched in months.
 *
 * <h2>Housekeeping, not correctness</h2>
 *
 * <p>Worth being clear about, because the equivalent sweeper for inventory reservations exists for
 * an entirely different reason. A stale <em>reservation</em> holds stock the shop cannot sell, so
 * leaving one is a real cost that grows. A stale <em>basket</em> holds nothing at all: it blocks no
 * stock, affects no total, and costs a few hundred bytes.
 *
 * <p>Which is why the threshold is months rather than hours, and why this deletes rather than
 * reports. Getting it wrong loses somebody a shopping list, and a customer who comes back after a
 * long absence to find their basket emptied is a worse outcome than a table with some dead rows
 * in it. When in doubt, wait longer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbandonedCartSweeper {

    private final CartRepository carts;
    private final OrderProperties properties;

    /**
     * Once a day, at four in the morning.
     *
     * <p>Not every ten minutes. Nothing here is urgent, and a sweep whose only effect is to
     * reclaim disk should not be competing with checkout traffic.
     */
    @Scheduled(cron = "${commerceflow.order.abandoned-cart-sweep-cron:0 0 4 * * *}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(properties.getAbandonedCartRetention());
        List<Cart> abandoned = carts.findAbandonedBefore(cutoff);

        if (abandoned.isEmpty()) {
            return;
        }

        carts.deleteAll(abandoned);
        log.info("Removed {} basket(s) untouched since {}", abandoned.size(), cutoff);
    }
}
