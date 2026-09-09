-- =====================================================================================
-- Saga orchestration state (ADR-011, superseding ADR-004).
--
-- Under choreography the flow of an order existed only as the sum of what four services
-- happened to subscribe to. There was no row anywhere that could answer "which step is this
-- order on, and what is it waiting for" — the question had to be reconstructed from logs.
--
-- These two tables are that answer:
--   * saga_instance  — the live position of one order's saga: current step, outstanding
--                      command, deadline, and the handles compensation will need
--   * saga_step_log  — the append-and-close history: every command sent, every reply,
--                      every retry, with the latency between the two halves of each step
--
-- Both live in the Order Service database on purpose. The orchestrator advances the saga and
-- the order aggregate in one transaction, so the two can never disagree about whether an
-- order was cancelled.
-- =====================================================================================

CREATE TABLE saga_instance (
    id                 UUID         NOT NULL,   -- equal to orders.id: one order, one saga
    order_number       VARCHAR(32)  NOT NULL,
    user_id            UUID         NOT NULL,
    user_email         VARCHAR(255) NOT NULL,
    state              VARCHAR(16)  NOT NULL,
    current_step       VARCHAR(32),             -- NULL once the saga is terminal
    current_command_id UUID,                    -- a reply's causation_id should match this
    attempt            INTEGER      NOT NULL DEFAULT 1,
    step_deadline      TIMESTAMPTZ,             -- NULL when nothing is outstanding
    reservation_id     UUID,                    -- the handle the release step needs
    payment_id         UUID,
    failed_step        VARCHAR(32),
    failure_reason     VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_saga_instance PRIMARY KEY (id),
    CONSTRAINT fk_saga_instance_order FOREIGN KEY (id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT ck_saga_instance_state CHECK (
        state IN ('STARTED', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'STALLED')),
    CONSTRAINT ck_saga_instance_step CHECK (
        current_step IS NULL OR current_step IN (
            'RESERVE_INVENTORY', 'PROCESS_PAYMENT', 'CONFIRM_INVENTORY',
            'RELEASE_INVENTORY', 'NOTIFY_CUSTOMER')),
    CONSTRAINT ck_saga_instance_attempt CHECK (attempt >= 1)
);

-- The timeout scanner's only query: running sagas whose deadline has passed, oldest first.
CREATE INDEX idx_saga_state_deadline ON saga_instance (state, step_deadline);
CREATE INDEX idx_saga_order_number ON saga_instance (order_number);

COMMENT ON TABLE saga_instance IS
    'Live position of one order saga. The orchestrator decides every next step from this row.';
COMMENT ON COLUMN saga_instance.step_deadline IS
    'When the current step stops being slow and starts being a problem; NULL when idle';
COMMENT ON COLUMN saga_instance.reservation_id IS
    'Held so compensation never has to ask Inventory what to undo';

CREATE TABLE saga_step_log (
    id             UUID         NOT NULL,
    saga_id        UUID         NOT NULL,
    step           VARCHAR(32)  NOT NULL,
    compensation   BOOLEAN      NOT NULL DEFAULT FALSE,
    attempt        INTEGER      NOT NULL DEFAULT 1,
    status         VARCHAR(16)  NOT NULL,
    command_id     UUID         NOT NULL,
    reply_event_id UUID,
    detail         VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ,
    CONSTRAINT pk_saga_step_log PRIMARY KEY (id),
    CONSTRAINT fk_saga_step_log_saga FOREIGN KEY (saga_id)
        REFERENCES saga_instance (id) ON DELETE CASCADE,
    CONSTRAINT uk_saga_step_log_command UNIQUE (command_id),
    CONSTRAINT ck_saga_step_log_status CHECK (
        status IN ('SENT', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'))
);

-- "Show me everything that happened to this order, in order."
CREATE INDEX idx_saga_step_log_saga ON saga_step_log (saga_id, created_at);
-- A reply closes its row by the command id it echoes back as causation_id.
CREATE INDEX idx_saga_step_log_command ON saga_step_log (command_id);

COMMENT ON TABLE saga_step_log IS
    'Append-and-close audit trail: one row per command sent, closed by the reply that answered it';
COMMENT ON COLUMN saga_step_log.attempt IS
    'Which send this was; a re-sent step leaves one row per attempt on purpose';
