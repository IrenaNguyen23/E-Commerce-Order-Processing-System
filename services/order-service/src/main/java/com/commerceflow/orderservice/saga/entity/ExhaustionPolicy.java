package com.commerceflow.orderservice.saga.entity;

/**
 * What the orchestrator does when a step has been re-sent its maximum number of times and still
 * has not been answered.
 *
 * <p>Parking everything was the first implementation and it was wrong in one specific, expensive
 * way: a reserve step that goes unanswered leaves stock held for an order that will never be
 * placed, and a release that goes unanswered leaves stock held for an order that was already
 * cancelled. Neither needs a human — in both cases the correct action is knowable — and both
 * silently reduce what the shop can sell.
 *
 * <p>So the policy is per step, and it turns on one question: <b>could money have moved?</b>
 */
public enum ExhaustionPolicy {

    /**
     * Unwind the saga without asking anyone.
     *
     * <p>Only for steps reached before any payment command was ever sent. Nothing has been
     * charged, so cancelling the order and giving the stock back cannot be wrong — the worst case
     * is a release for a reservation that was never created, which participants answer happily.
     */
    ABANDON,

    /**
     * Keep re-sending, indefinitely, and shout about it.
     *
     * <p>For compensation steps. The decision to undo has already been made and recorded; giving
     * up on it is precisely what strands the thing being undone. Retrying is safe because the
     * participant answers the same way every time, so the only cost of persisting is log noise —
     * against the cost of never giving stock back.
     */
    KEEP_TRYING,

    /**
     * Stop, and page a human.
     *
     * <p>For steps where the outcome is genuinely unknown and guessing is worse than waiting. A
     * payment step that has gone quiet may already have taken the customer's money: releasing the
     * stock would oversell it, and completing the order would ship goods that were never paid for.
     * Neither is a call software should make on its own.
     */
    PARK
}
