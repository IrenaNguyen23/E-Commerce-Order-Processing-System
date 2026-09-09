-- =====================================================================================
-- Email verification and password reset.
--
-- Both flows are the same mechanism with a different purpose: issue a high-entropy token,
-- email it, accept it back once, within a window. So they share one table rather than two
-- near-identical ones — the expiry sweep, the single-use rule and the hashing are written
-- once and cannot drift apart.
-- =====================================================================================

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Accounts that existed before verification did are treated as verified. They were created
-- when the platform made no such claim, and retroactively locking them out of a feature they
-- never opted into would be a worse answer than trusting them.
UPDATE users SET email_verified = TRUE;

COMMENT ON COLUMN users.email_verified IS
    'Whether the address has been proven reachable. Enforcement at login is a separate switch.';

CREATE TABLE verification_tokens (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    purpose      VARCHAR(32)  NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_verification_tokens PRIMARY KEY (id),
    CONSTRAINT fk_verification_tokens_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_verification_tokens_purpose CHECK (
        purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'))
);

-- The lookup path: a token arrives, and it is found by its digest or it is not valid.
-- Unique because a collision would let one token consume another's grant.
CREATE UNIQUE INDEX uk_verification_token_hash ON verification_tokens (token_hash);

-- Invalidating a user's outstanding tokens for one purpose, which happens every time a new
-- one is issued and again when a reset completes.
CREATE INDEX idx_verification_user_purpose ON verification_tokens (user_id, purpose);

-- The retention sweep.
CREATE INDEX idx_verification_expires ON verification_tokens (expires_at);

COMMENT ON TABLE verification_tokens IS
    'Single-use, expiring grants emailed to a user. Only the SHA-256 digest is stored.';
COMMENT ON COLUMN verification_tokens.token_hash IS
    'Hex SHA-256 of the token; the value itself exists only in the email that was sent';
COMMENT ON COLUMN verification_tokens.consumed_at IS
    'Set the moment the token is used. A non-null value makes it permanently unusable.';
