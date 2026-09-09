-- =====================================================================================
-- C9 — the customer address book.
--
-- Nothing outside this service references these rows. An order copies the address at
-- checkout, which is what makes the DELETE below safe and what keeps a corrected typo
-- from rewriting where last year's parcels were sent.
-- =====================================================================================

CREATE TABLE user_addresses (
    id              UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    label           VARCHAR(50),
    type            VARCHAR(16)  NOT NULL DEFAULT 'BOTH',
    recipient_name  VARCHAR(150) NOT NULL,
    phone           VARCHAR(32),
    line1           VARCHAR(200) NOT NULL,
    line2           VARCHAR(200),
    city            VARCHAR(100) NOT NULL,
    region          VARCHAR(100),
    postal_code     VARCHAR(20),
    country_code    CHAR(2)      NOT NULL,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_addresses PRIMARY KEY (id),
    CONSTRAINT fk_user_addresses_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_addresses_country CHECK (country_code = UPPER(country_code)),
    CONSTRAINT ck_user_addresses_type CHECK (type IN ('SHIPPING', 'BILLING', 'BOTH'))
);

COMMENT ON TABLE user_addresses IS
    'Saved addresses. A convenience for the checkout form; orders copy rather than reference.';
COMMENT ON COLUMN user_addresses.country_code IS
    'ISO-3166 alpha-2, upper case. Input to the tax rate and the shipping zone.';

CREATE INDEX idx_user_addresses_user ON user_addresses (user_id);

-- One default per customer, enforced where it cannot be raced.
--
-- The service clears the previous default in the same transaction, which handles every
-- ordinary request. This index handles the one it cannot: two "make this my default"
-- calls arriving together, which without it leave a customer with two defaults and a
-- checkout form that picks whichever the query happened to return first.
CREATE UNIQUE INDEX uk_user_addresses_one_default
    ON user_addresses (user_id) WHERE is_default;
