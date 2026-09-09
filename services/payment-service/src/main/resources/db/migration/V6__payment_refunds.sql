-- =====================================================================================
-- Refunds as their own record, so a partial one is possible and a repeated one is safe.
--
-- Until now a refund was saga compensation: reverse the whole payment, set the row to
-- REFUNDED, and answer any repeat with "already done". That is exactly right for a cancelled
-- order and exactly wrong for a return, which is frequently partial and can happen more than
-- once for the same order weeks apart.
--
-- Routed through the old path, the first partial refund would have marked the payment fully
-- refunded and the second would have reported success while sending nothing. The bug would
-- have surfaced as a customer who returned two items and was paid for one.
--
-- So each refund is a row. What has been given back is the sum of them, and what may still be
-- given back is the payment less that sum. Both are facts rather than a flag.
-- =====================================================================================

CREATE TABLE payment_refunds (
    id                 UUID           NOT NULL,
    payment_id         UUID           NOT NULL,
    order_id           UUID           NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    currency           CHAR(3)        NOT NULL,
    reason             VARCHAR(255),

    -- The acquirer's reference for this movement. Null when the attempt failed, which is a
    -- state worth being able to see: it means the customer is owed and nobody has sent it.
    external_reference VARCHAR(128),
    succeeded          BOOLEAN        NOT NULL,
    failure_reason     VARCHAR(255),

    -- The return this settles. THE idempotency key.
    --
    -- Deliberately a business identifier rather than the message id: the same return must
    -- refund once however many times the command is delivered, and however many times a
    -- relay republishes it after a restart. A message id would only deduplicate one delivery
    -- of one message.
    idempotency_key    VARCHAR(64)    NOT NULL,

    created_at         TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_payment_refunds PRIMARY KEY (id),
    CONSTRAINT fk_payment_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id),

    -- The whole guarantee, enforced by the database rather than by remembering to check.
    -- A second attempt for the same return cannot insert, whatever the application does.
    CONSTRAINT uk_payment_refunds_key UNIQUE (idempotency_key),

    CONSTRAINT ck_payment_refunds_amount CHECK (amount > 0)
);

COMMENT ON TABLE payment_refunds IS
    'One movement of money back to a customer. The sum of the successful rows for a payment is
     what has been returned; the payment amount less that sum is what may still be returned.';

COMMENT ON COLUMN payment_refunds.succeeded IS
    'Failed attempts are kept, not deleted. A failed refund is a debt the shop owes and the
     row is the only evidence it was ever attempted.';

CREATE INDEX idx_payment_refunds_payment ON payment_refunds (payment_id);
CREATE INDEX idx_payment_refunds_order ON payment_refunds (order_id);
