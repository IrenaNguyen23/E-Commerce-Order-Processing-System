-- =====================================================================================
-- Notification Service schema (database commerceflow_notification).
--
-- Flyway owns the schema (ADR-007); Hibernate runs with ddl-auto=validate.
-- =====================================================================================

CREATE TABLE notifications (
    id             UUID         NOT NULL,
    user_id        UUID,
    reference_id   UUID,
    type           VARCHAR(32)  NOT NULL,
    channel        VARCHAR(16)  NOT NULL,
    recipient      VARCHAR(255) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    content        TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    failure_reason VARCHAR(500),
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    sent_at        TIMESTAMPTZ,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT ck_notifications_type CHECK (
        type IN ('USER_WELCOME', 'ORDER_CONFIRMED', 'ORDER_CANCELLED', 'GENERIC'))
);

COMMENT ON COLUMN notifications.reference_id IS 'Business entity this notification is about, typically an order id';

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_reference ON notifications (reference_id);
CREATE INDEX idx_notifications_status ON notifications (status);

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
