-- =====================================================================================
-- C5 -- discount codes.
--
-- The interesting column is coupons.redemption_count, and the interesting thing about it
-- is that no application code ever reads it, adds one and writes it back. That is a race
-- with a business consequence: a code meant for the first hundred customers is honoured
-- a hundred and eleven times, and nothing anywhere reports an error. The increment is a
-- single conditional UPDATE and its row count is the answer.
-- =====================================================================================

CREATE TABLE coupons (
    id                 UUID           NOT NULL,
    code               VARCHAR(40)    NOT NULL,
    description        VARCHAR(200),
    type               VARCHAR(20)    NOT NULL,
    value              NUMERIC(19, 4) NOT NULL,
    currency           CHAR(3),
    minimum_basket     NUMERIC(19, 4),
    max_redemptions    INTEGER,
    redemption_count   INTEGER        NOT NULL DEFAULT 0,
    per_customer_limit INTEGER,
    valid_from         TIMESTAMPTZ,
    valid_until        TIMESTAMPTZ,
    active             BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_coupons PRIMARY KEY (id),
    CONSTRAINT uk_coupons_code UNIQUE (code),
    CONSTRAINT ck_coupons_type CHECK (type IN ('PERCENTAGE', 'FIXED_AMOUNT', 'FREE_SHIPPING')),
    CONSTRAINT ck_coupons_value CHECK (value >= 0),
    CONSTRAINT ck_coupons_window CHECK (valid_until IS NULL OR valid_from IS NULL
                                        OR valid_until > valid_from),

    -- The counter can never exceed the cap, whatever writes it. The conditional UPDATE is
    -- what enforces this in practice; the constraint is what makes a future bug -- a
    -- migration, an operator, a well meant setter -- fail loudly instead of overselling.
    CONSTRAINT ck_coupons_allowance CHECK (max_redemptions IS NULL
                                           OR redemption_count <= max_redemptions),
    CONSTRAINT ck_coupons_count CHECK (redemption_count >= 0)
);

COMMENT ON TABLE coupons IS
    'Discount codes. Codes are stored and matched upper case, because "welcome10" and
     "WELCOME10" are the same code to everybody except a database.';
COMMENT ON COLUMN coupons.value IS
    'A fraction for PERCENTAGE (0.1000 is 10%), an amount for FIXED_AMOUNT, ignored for
     FREE_SHIPPING.';
COMMENT ON COLUMN coupons.redemption_count IS
    'Incremented ONLY by the conditional UPDATE in CouponRepository#claim. Never by a
     read-modify-write: that is how a limited campaign quietly stops being limited.';

CREATE INDEX idx_coupons_active ON coupons (active);

-- ---------------------------------------------------------------- the ledger
--
-- A counter says how many times a code was used. It cannot say whether THIS customer has
-- used it, and it cannot be undone accurately when an order is cancelled. This can.

CREATE TABLE coupon_redemptions (
    id              UUID           NOT NULL,
    coupon_id       UUID           NOT NULL,
    code            VARCHAR(40)    NOT NULL,
    user_id         UUID           NOT NULL,
    order_id        UUID           NOT NULL,
    discount_amount NUMERIC(19, 4) NOT NULL,
    currency        CHAR(3)        NOT NULL,
    redeemed_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_coupon_redemptions PRIMARY KEY (id),
    CONSTRAINT fk_coupon_redemptions_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id),

    -- Makes claiming idempotent: a retried checkout cannot spend the same allowance twice.
    CONSTRAINT uk_coupon_redemptions_order UNIQUE (coupon_id, order_id)
);

COMMENT ON TABLE coupon_redemptions IS
    'One use of one code on one order. Deleted when the order is cancelled, and the
     campaign''s counter goes back with it -- a declined card is not a spent coupon.';

CREATE INDEX idx_coupon_redemptions_coupon_user ON coupon_redemptions (coupon_id, user_id);
CREATE INDEX idx_coupon_redemptions_order ON coupon_redemptions (order_id);

-- ---------------------------------------------------------------- the order remembers
ALTER TABLE orders          ADD COLUMN coupon_code VARCHAR(40);
ALTER TABLE order_read_model ADD COLUMN coupon_code VARCHAR(40);

COMMENT ON COLUMN orders.coupon_code IS
    'The code as text, not a foreign key. A campaign can be renamed or deleted and this
     order still has to explain what came off it -- the same rule as the product name.';

-- ---------------------------------------------------------------- opening campaigns
INSERT INTO coupons
    (id, code, description, type, value, currency, minimum_basket, max_redemptions,
     redemption_count, per_customer_limit, valid_from, valid_until, active, created_at, updated_at)
VALUES
    ('c1000000-0000-4000-8000-000000000001', 'WELCOME10',
     '10% off your first order', 'PERCENTAGE', 0.1000, NULL, 50.0000, NULL, 0, 1,
     NULL, NULL, TRUE, NOW(), NOW()),

    ('c1000000-0000-4000-8000-000000000002', 'FREESHIP',
     'Free delivery, any basket', 'FREE_SHIPPING', 0.0000, NULL, NULL, NULL, 0, NULL,
     NULL, NULL, TRUE, NOW(), NOW()),

    -- Capped, so the conditional claim has something to actually refuse. Worth having in
    -- seed data: a limit that is never reached is a limit that was never tested.
    ('c1000000-0000-4000-8000-000000000003', 'TENOFF',
     'EUR 10 off orders over EUR 100', 'FIXED_AMOUNT', 10.0000, 'EUR', 100.0000, 100, 0, 2,
     NULL, NULL, TRUE, NOW(), NOW()),

    -- Expired on purpose, so the "has expired" message is reachable without waiting.
    ('c1000000-0000-4000-8000-000000000004', 'SUMMER24',
     'Last summer''s campaign', 'PERCENTAGE', 0.2000, NULL, NULL, NULL, 0, NULL,
     NOW() - INTERVAL '400 days', NOW() - INTERVAL '365 days', TRUE, NOW(), NOW());

-- ---------------------------------------------------------------- category slug snapshot
--
-- The order line already copies the category's display NAME, for the invoice. It now also
-- copies the slug, because those two are used for different things and only one of them is
-- safe to key on: tax rates are looked up by category, and keying that on a name means a
-- merchandiser renaming a section changes the tax charged on everything inside it -- with
-- no error, just a different figure on the next order.
ALTER TABLE order_items ADD COLUMN product_category_slug VARCHAR(100);

COMMENT ON COLUMN order_items.product_category_slug IS
    'The category slug at order time; what the tax rate was matched against. The display
     name is in product_category and may have been renamed since.';

COMMENT ON COLUMN tax_rates.category IS
    'A category SLUG, not a display name. NULL is the country standard rate.';
