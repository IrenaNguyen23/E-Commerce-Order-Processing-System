-- =====================================================================================
-- Auth Service schema (database commerceflow_auth).
--
-- Flyway owns the schema (ADR-007); Hibernate runs with ddl-auto=validate and only checks
-- that the JPA mapping still matches what is created here.
-- =====================================================================================

CREATE TABLE users (
    id              UUID         NOT NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(150) NOT NULL,
    phone           VARCHAR(32),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

COMMENT ON TABLE users IS 'Registered accounts. Passwords are stored as BCrypt hashes only.';

-- Case-insensitive uniqueness: the application lower-cases addresses before writing, this
-- index makes the guarantee hold even for rows written by an operator or a data migration.
CREATE UNIQUE INDEX uk_users_email_lower ON users (LOWER(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL,
    role    VARCHAR(32) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE refresh_tokens (
    id          UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID,
    created_at  TIMESTAMPTZ NOT NULL,
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(64),
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

COMMENT ON COLUMN refresh_tokens.id IS 'Equals the jti claim of the issued refresh token';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'Hex SHA-256 of the token value; the value itself is never stored';

CREATE INDEX idx_refresh_token_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_token_expires ON refresh_tokens (expires_at);

-- =====================================================================================
-- Transactional outbox (ADR-006). Owned by commerceflow-common, one table per service.
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
