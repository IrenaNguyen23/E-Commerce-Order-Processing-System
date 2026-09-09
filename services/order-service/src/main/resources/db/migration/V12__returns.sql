-- =====================================================================================
-- Returns.
--
-- WHY THIS IS NOT A NEW ORDER STATUS
--
-- orders.status is the saga's state machine: CREATED -> INVENTORY_RESERVED -> PAID ->
-- COMPLETED, or CANCELLED. Every one of those is set by the orchestrator, and the guard that
-- stops a late message moving an order backwards depends on that being true.
--
-- A return happens after the saga is finished and has nothing to do with it. Adding RETURNED
-- to that enum would put a state in the machine that the machine never sets, and the next
-- person reading the orchestrator would look for the transition and not find it.
--
-- This is the same decision already taken for shipments, for the same reason: an order is
-- COMPLETED while its parcel goes from PENDING to DELIVERED, and it stays COMPLETED while the
-- parcel comes back. One order, several lifecycles, each on its own clock.
--
-- WHAT AN ORDER'S RETURN STATE IS, THEN
--
-- The rows below. "Has this order been returned" is a question about these rows, exactly as
-- "where is this parcel" is a question about the shipment rows.
-- =====================================================================================

CREATE TABLE return_requests (
    id                UUID           NOT NULL,
    order_id          UUID           NOT NULL,
    order_number      VARCHAR(32)    NOT NULL,
    user_id           UUID           NOT NULL,

    -- Copied, like everything else on an order snapshot: the account may be renamed, or erased,
    -- and a warehouse still has to know who is sending a parcel back.
    user_email        VARCHAR(255)   NOT NULL,

    status            VARCHAR(20)    NOT NULL,

    -- The customer's words. Kept verbatim rather than reduced to a code, because "arrived
    -- with a cracked screen" and "changed my mind" need different handling and a dropdown
    -- always lacks the case in front of you.
    reason            VARCHAR(500)   NOT NULL,

    -- Money, frozen when the request is raised.
    --
    -- Computed from the order's own line prices at that moment, not recomputed later: a
    -- catalogue price change between the request and the refund must not alter what somebody
    -- gets back.
    refund_amount     NUMERIC(19, 4) NOT NULL,
    currency          CHAR(3)        NOT NULL,

    -- Whether the original delivery charge is included. True only when the whole order came
    -- back, which is what the returns policy promises.
    refund_shipping   BOOLEAN        NOT NULL DEFAULT FALSE,

    requested_at      TIMESTAMPTZ    NOT NULL,
    decided_at        TIMESTAMPTZ,
    decided_by        UUID,
    decision_note     VARCHAR(500),
    received_at       TIMESTAMPTZ,
    refunded_at       TIMESTAMPTZ,

    -- The acquirer's reference, once there is one. What reconciliation matches against.
    refund_reference  VARCHAR(128),

    -- Why the refund did not go through, when it did not. Kept on the row rather than only in
    -- a log, because it is what the person picking this up tomorrow needs to see.
    refund_failure    VARCHAR(255),

    version           BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_return_requests PRIMARY KEY (id),
    CONSTRAINT fk_return_requests_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_return_requests_status CHECK (status IN
        ('REQUESTED', 'APPROVED', 'REJECTED', 'RECEIVED',
         'REFUND_PENDING', 'REFUNDED', 'CANCELLED')),
    CONSTRAINT ck_return_requests_amount CHECK (refund_amount >= 0)
);

COMMENT ON TABLE return_requests IS
    'One request to send goods back. Its lifecycle is separate from the order saga: see the
     header of this migration.';

COMMENT ON COLUMN return_requests.status IS
    'REQUESTED -> APPROVED -> RECEIVED -> REFUND_PENDING -> REFUNDED is the ordinary path.
     REJECTED ends it after a decision, CANCELLED ends it when the customer changes their mind
     before one. REFUND_PENDING means the refund command is out and the answer has not come
     back; a failure returns it to RECEIVED with refund_failure set, so it can be retried.';

CREATE INDEX idx_return_requests_order ON return_requests (order_id);
CREATE INDEX idx_return_requests_user ON return_requests (user_id, requested_at DESC);

-- The queue an operator works: everything awaiting a decision, oldest first.
CREATE INDEX idx_return_requests_status ON return_requests (status, requested_at);

CREATE TABLE return_request_items (
    id                UUID           NOT NULL,
    return_request_id UUID           NOT NULL,
    order_item_id     UUID           NOT NULL,
    product_id        UUID           NOT NULL,

    -- Snapshotted from the order line, which snapshotted it from the catalogue. A product
    -- renamed since the order was placed must not rename itself on the return paperwork.
    product_name      VARCHAR(200)   NOT NULL,
    sku               VARCHAR(64)    NOT NULL,

    quantity          INTEGER        NOT NULL,

    -- What this line gives back: net at the price actually charged, plus tax recomputed at the
    -- rate frozen on the order. Per line and rounded once, never apportioned back out of an
    -- order total -- the same rule the order itself follows, and for the same reason.
    refund_amount     NUMERIC(19, 4) NOT NULL,

    CONSTRAINT pk_return_request_items PRIMARY KEY (id),
    CONSTRAINT fk_return_request_items_request FOREIGN KEY (return_request_id)
        REFERENCES return_requests (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_items_line FOREIGN KEY (order_item_id)
        REFERENCES order_items (id),

    -- One row per order line per request. Two lines for the same item in one request would
    -- make the returned quantity a sum nobody thinks to compute.
    CONSTRAINT uk_return_request_items_line UNIQUE (return_request_id, order_item_id),

    CONSTRAINT ck_return_request_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_return_request_items_amount CHECK (refund_amount >= 0)
);

CREATE INDEX idx_return_request_items_request ON return_request_items (return_request_id);

-- Used to work out how much of a line has already been sent back, so a second request cannot
-- return more than was bought.
CREATE INDEX idx_return_request_items_line ON return_request_items (order_item_id);
