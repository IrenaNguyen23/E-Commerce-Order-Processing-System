-- =====================================================================================
-- Payment Service schema (database commerceflow_payment).
--
-- Flyway owns the schema (ADR-007); Hibernate runs with ddl-auto=validate.
-- =====================================================================================

CREATE TABLE payments (
    id             UUID           NOT NULL,
    order_id       UUID           NOT NULL,
    order_number   VARCHAR(32),
    user_id        UUID,
    user_email     VARCHAR(255),
    amount         NUMERIC(19, 4) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    method         VARCHAR(24)    NOT NULL,
    transaction_id VARCHAR(64),
    failure_reason VARCHAR(255),
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    processed_at   TIMESTAMPTZ,
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    -- The strongest guarantee in this service: a customer can only ever be charged once per
    -- order, whatever happens upstream.
    CONSTRAINT uk_payments_order UNIQUE (order_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payments_method CHECK (method IN ('CARD', 'IDEAL', 'PAYPAL', 'BANK_TRANSFER')),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payments_user ON payments (user_id);
CREATE INDEX idx_payments_status ON payments (status);
CREATE INDEX idx_payments_created ON payments (created_at);

COMMENT ON COLUMN payments.transaction_id IS 'Acquirer reference; the key for reconciliation and refunds';

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
