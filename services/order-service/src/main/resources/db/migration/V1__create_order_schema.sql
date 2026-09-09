-- =====================================================================================
-- Order Service schema (database commerceflow_order).
--
-- Holds both sides of the CQRS split:
--   * orders / order_items  — the write model, the system of record
--   * order_read_model      — the denormalised query side, projected in the same transaction
--
-- Flyway owns the schema (ADR-007); Hibernate runs with ddl-auto=validate.
-- =====================================================================================

-- Human readable order numbers: monotonic, gap tolerant, safe across replicas.
CREATE SEQUENCE order_number_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE orders (
    id               UUID           NOT NULL,
    order_number     VARCHAR(32)    NOT NULL,
    user_id          UUID           NOT NULL,
    user_email       VARCHAR(255)   NOT NULL,
    status           VARCHAR(24)    NOT NULL,
    total_amount     NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    shipping_address VARCHAR(500)   NOT NULL,
    idempotency_key  VARCHAR(64),
    payment_id       UUID,
    failure_reason   VARCHAR(255),
    failed_step      VARCHAR(32),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    completed_at     TIMESTAMPTZ,
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_number UNIQUE (order_number),
    CONSTRAINT ck_orders_status CHECK (
        status IN ('CREATED', 'INVENTORY_RESERVED', 'PAID', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_orders_total_non_negative CHECK (total_amount >= 0)
);

CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created ON orders (created_at);

-- A retried create request must return the original order rather than placing a second one.
CREATE UNIQUE INDEX uk_orders_user_idempotency
    ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE order_items (
    id           UUID           NOT NULL,
    order_id     UUID           NOT NULL,
    product_id   UUID           NOT NULL,
    sku          VARCHAR(64)    NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    quantity     INTEGER        NOT NULL,
    unit_price   NUMERIC(19, 4) NOT NULL,
    subtotal     NUMERIC(19, 4) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

COMMENT ON COLUMN order_items.unit_price IS
    'Snapshotted at order time: a later catalogue price change must not rewrite history';

-- =====================================================================================
-- CQRS read model. Derived data: it can be rebuilt from orders / order_items at any time.
-- =====================================================================================

CREATE TABLE order_read_model (
    order_id         UUID           NOT NULL,
    order_number     VARCHAR(32)    NOT NULL,
    user_id          UUID           NOT NULL,
    user_email       VARCHAR(255)   NOT NULL,
    status           VARCHAR(24)    NOT NULL,
    total_amount     NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    shipping_address VARCHAR(500)   NOT NULL,
    item_count       INTEGER        NOT NULL DEFAULT 0,
    items_json       TEXT           NOT NULL,
    payment_id       UUID,
    failure_reason   VARCHAR(255),
    failed_step      VARCHAR(32),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    completed_at     TIMESTAMPTZ,
    CONSTRAINT pk_order_read_model PRIMARY KEY (order_id),
    CONSTRAINT ck_order_read_model_status CHECK (
        status IN ('CREATED', 'INVENTORY_RESERVED', 'PAID', 'COMPLETED', 'CANCELLED'))
);

-- Covers the customer-facing "my orders" page: one index, no joins.
CREATE INDEX idx_order_view_user_created ON order_read_model (user_id, created_at DESC);
CREATE INDEX idx_order_view_status ON order_read_model (status);
CREATE INDEX idx_order_view_number ON order_read_model (order_number);

-- =====================================================================================
-- Shared messaging infrastructure (commerceflow-common): outbox + idempotency ledger.
-- =====================================================================================

CREATE TABLE outbox_event (
    id             UUID         NOT NULL,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    topic          VARCHAR(128) NOT NULL,
    partition_key  VARCHAR(128),
    payload        TEXT         NOT NULL,
    correlation_id VARCHAR(64),
    status         VARCHAR(16)  NOT NULL,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_status_created ON outbox_event (status, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_event (aggregate_type, aggregate_id);

CREATE TABLE processed_event (
    consumer_group VARCHAR(128) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX idx_processed_event_processed_at ON processed_event (processed_at);
