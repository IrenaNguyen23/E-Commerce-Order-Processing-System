package com.commerceflow.orderservice.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerceflow.orderservice.saga.entity.ExhaustionPolicy;
import com.commerceflow.orderservice.saga.entity.SagaStep;

/**
 * The safety rule, on its own.
 *
 * <p>{@link SagaStep#onRetriesExhausted()} decides whether the orchestrator may unwind a saga by
 * itself. Getting one entry wrong does not fail a build or throw at runtime — it either strands
 * stock forever or releases stock the customer has already paid for, both of which surface days
 * later as a discrepancy nobody can explain. So the mapping is pinned here, one step at a time,
 * with the reason next to it.
 */
class ExhaustionPolicyTest {

    @Test
    @DisplayName("a reserve that never answers unwinds itself: no money can have moved")
    void reserveAbandons() {
        assertThat(SagaStep.RESERVE_INVENTORY.onRetriesExhausted())
                .isEqualTo(ExhaustionPolicy.ABANDON);
    }

    @Test
    @DisplayName("a payment that never answers parks: the charge may or may not have happened")
    void paymentParks() {
        // The single most consequential entry in this table. Abandoning here would cancel orders
        // that were paid for and put their stock back on sale.
        assertThat(SagaStep.PROCESS_PAYMENT.onRetriesExhausted())
                .isEqualTo(ExhaustionPolicy.PARK);
    }

    @Test
    @DisplayName("a confirm that never answers parks: the goods are sold, so holding them is right")
    void confirmParks() {
        assertThat(SagaStep.CONFIRM_INVENTORY.onRetriesExhausted())
                .isEqualTo(ExhaustionPolicy.PARK);
    }

    @Test
    @DisplayName("a release that never answers keeps trying: giving up is what strands the stock")
    void releaseKeepsTrying() {
        // The order is already cancelled and the customer is not being charged. There is no
        // outcome to weigh up — the units simply have to go back.
        assertThat(SagaStep.RELEASE_INVENTORY.onRetriesExhausted())
                .isEqualTo(ExhaustionPolicy.KEEP_TRYING);
    }

    @Test
    @DisplayName("a notification that never answers parks: nothing is stranded by waiting")
    void notifyParks() {
        assertThat(SagaStep.NOTIFY_CUSTOMER.onRetriesExhausted())
                .isEqualTo(ExhaustionPolicy.PARK);
    }

    @Test
    @DisplayName("only steps reached before the payment command may abandon")
    void nothingAfterPaymentAbandons() {
        // The invariant behind the whole table, asserted as a rule rather than a list — so a step
        // added later cannot quietly opt into abandoning after money has moved.
        assertThat(Arrays.stream(SagaStep.values())
                .filter(step -> step.onRetriesExhausted() == ExhaustionPolicy.ABANDON)
                .toList())
                .containsExactly(SagaStep.RESERVE_INVENTORY);
    }

    @Test
    @DisplayName("every step has a policy")
    void everyStepIsCovered() {
        assertThat(Arrays.stream(SagaStep.values()).map(SagaStep::onRetriesExhausted))
                .doesNotContainNull()
                .hasSize(SagaStep.values().length);
    }
}
