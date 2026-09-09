-- =====================================================================================
-- Inventory Service schema (database commerceflow_inventory).
--
-- Flyway owns the schema (ADR-007); Hibernate runs with ddl-auto=validate.
-- =====================================================================================

CREATE TABLE products (
    id          UUID          NOT NULL,
    sku         VARCHAR(64)   NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(2000),
    category    VARCHAR(100),
    price       NUMERIC(19, 4) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    image_url   VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price_positive CHECK (price > 0)
);

CREATE INDEX idx_products_category ON products (category);
CREATE INDEX idx_products_active ON products (active);
CREATE UNIQUE INDEX uk_products_sku_upper ON products (UPPER(sku));

-- Stock is a separate row from the catalogue entry: it changes on every order, the catalogue
-- entry almost never does, and keeping them apart stops one from locking the other.
CREATE TABLE inventory_items (
    product_id         UUID        NOT NULL,
    sku                VARCHAR(64) NOT NULL,
    available_quantity INTEGER     NOT NULL DEFAULT 0,
    reserved_quantity  INTEGER     NOT NULL DEFAULT 0,
    reorder_level      INTEGER     NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_inventory_items PRIMARY KEY (product_id),
    CONSTRAINT fk_inventory_items_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT ck_inventory_available_non_negative CHECK (available_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_non_negative CHECK (reserved_quantity >= 0)
);

COMMENT ON COLUMN inventory_items.available_quantity IS 'Units a new order may consume';
COMMENT ON COLUMN inventory_items.reserved_quantity IS 'Units held by orders whose saga is still running';

CREATE TABLE inventory_reservations (
    id           UUID        NOT NULL,
    order_id     UUID        NOT NULL,
    order_number VARCHAR(32),
    user_id      UUID,
    status       VARCHAR(16) NOT NULL,
    reason       VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_inventory_reservations PRIMARY KEY (id),
    -- Second line of defence for idempotency: one hold per order, enforced by the database.
    CONSTRAINT uk_inventory_reservations_order UNIQUE (order_id),
    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED', 'RELEASED', 'CONFIRMED', 'FAILED'))
);

CREATE INDEX idx_reservation_status ON inventory_reservations (status);

CREATE TABLE reservation_items (
    id             UUID        NOT NULL,
    reservation_id UUID        NOT NULL,
    product_id     UUID        NOT NULL,
    sku            VARCHAR(64) NOT NULL,
    quantity       INTEGER     NOT NULL,
    CONSTRAINT pk_reservation_items PRIMARY KEY (id),
    CONSTRAINT fk_reservation_items_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory_reservations (id) ON DELETE CASCADE,
    CONSTRAINT ck_reservation_items_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_items_reservation ON reservation_items (reservation_id);

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
