-- =====================================================================================
-- C4 — tax and delivery cost.
--
-- Three things happen here:
--   1. The destination becomes structured, because a country parsed out of free text is
--      a country that will eventually be parsed wrong, quietly, into a wrong total.
--   2. Orders gain tax and delivery columns, and their lines gain the rate that applied.
--   3. Rate cards arrive as tables, because a tax rate is changed by a legislature and a
--      delivery price by whoever runs the shop -- neither should need a deployment.
-- =====================================================================================

-- ---------------------------------------------------------------- the destination
ALTER TABLE orders
    ADD COLUMN recipient_name       VARCHAR(150),
    ADD COLUMN shipping_phone       VARCHAR(32),
    ADD COLUMN shipping_line1       VARCHAR(200),
    ADD COLUMN shipping_line2       VARCHAR(200),
    ADD COLUMN shipping_city        VARCHAR(100),
    ADD COLUMN shipping_region      VARCHAR(100),
    ADD COLUMN shipping_postal_code VARCHAR(20),
    ADD COLUMN shipping_country     CHAR(2),
    ADD COLUMN shipping_method      VARCHAR(16),
    ADD COLUMN delivery_min_days    INTEGER,
    ADD COLUMN delivery_max_days    INTEGER;

COMMENT ON COLUMN orders.shipping_country IS
    'ISO-3166 alpha-2. Not decoration: this is what chose the tax rate and the delivery rate.';
COMMENT ON COLUMN orders.shipping_address IS
    'The formatted one-liner, frozen at checkout. The structured columns are alongside it, not
     instead of it, so a later change to the formatter cannot alter an old order.';

-- Nullable, deliberately. Orders placed before this migration have a formatted address and no
-- structured one, and inventing a country for them would be inventing the tax they should have
-- paid. They keep what they have; every new order fills these in.

-- ---------------------------------------------------------------- the money
ALTER TABLE orders
    ADD COLUMN tax_total       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN shipping_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN orders.tax_total IS
    'Sum of the per-line tax amounts, each already rounded. Added up rather than derived, so the
     invoice adds up as printed.';
COMMENT ON COLUMN orders.total_amount IS
    'What was charged: subtotal_amount - discount_total + tax_total + shipping_amount.';

ALTER TABLE order_items
    ADD COLUMN tax_name   VARCHAR(50),
    ADD COLUMN tax_rate   NUMERIC(6, 4),
    ADD COLUMN tax_amount NUMERIC(19, 4);

COMMENT ON COLUMN order_items.tax_rate IS
    'The rate that applied, as a fraction. Snapshotted like the price: a rate change next April
     must not alter what this order was charged.';

-- ---------------------------------------------------------------- the read model
ALTER TABLE order_read_model
    ADD COLUMN tax_total            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN shipping_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN recipient_name       VARCHAR(150),
    ADD COLUMN shipping_phone       VARCHAR(32),
    ADD COLUMN shipping_line1       VARCHAR(200),
    ADD COLUMN shipping_line2       VARCHAR(200),
    ADD COLUMN shipping_city        VARCHAR(100),
    ADD COLUMN shipping_region      VARCHAR(100),
    ADD COLUMN shipping_postal_code VARCHAR(20),
    ADD COLUMN shipping_country     CHAR(2),
    ADD COLUMN shipping_method      VARCHAR(16),
    ADD COLUMN delivery_min_days    INTEGER,
    ADD COLUMN delivery_max_days    INTEGER;

-- ---------------------------------------------------------------- tax rate card
CREATE TABLE tax_rates (
    id           UUID           NOT NULL,
    country_code CHAR(2)        NOT NULL,
    category     VARCHAR(100),
    name         VARCHAR(50)    NOT NULL,
    rate         NUMERIC(6, 4)  NOT NULL,
    updated_at   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_tax_rates PRIMARY KEY (id),
    CONSTRAINT ck_tax_rates_rate CHECK (rate >= 0 AND rate < 1)
);

COMMENT ON TABLE tax_rates IS
    'What a category of goods attracts in one country. A NULL category is the country standard
     rate and is what an unlisted category falls back to. A country with no rows means no tax.';
COMMENT ON COLUMN tax_rates.rate IS 'A fraction, not a percentage: 0.2100 is 21%.';

CREATE UNIQUE INDEX uk_tax_rates_country_category
    ON tax_rates (country_code, COALESCE(category, ''));
CREATE INDEX idx_tax_rates_lookup ON tax_rates (country_code, category);

-- ---------------------------------------------------------------- delivery rate card
CREATE TABLE shipping_rates (
    id              UUID           NOT NULL,
    country_code    CHAR(2)        NOT NULL,
    method          VARCHAR(16)    NOT NULL,
    currency        CHAR(3)        NOT NULL,
    base_amount     NUMERIC(19, 4) NOT NULL,
    per_item_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    free_above      NUMERIC(19, 4),
    min_days        INTEGER        NOT NULL,
    max_days        INTEGER        NOT NULL,
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_shipping_rates PRIMARY KEY (id),
    CONSTRAINT uk_shipping_rates UNIQUE (country_code, method),
    CONSTRAINT ck_shipping_rates_days CHECK (min_days >= 0 AND max_days >= min_days),
    CONSTRAINT ck_shipping_rates_amounts CHECK (base_amount >= 0 AND per_item_amount >= 0)
);

COMMENT ON TABLE shipping_rates IS
    'A rate card, not a carrier quote. Products carry no weight, so a weight-based figure would be
     fiction. country_code = ''*'' is the rest-of-world row, so an unlisted destination is priced
     approximately rather than rejected at the last step of checkout.';
COMMENT ON COLUMN shipping_rates.free_above IS
    'Compared against the basket AFTER discounts, so a coupon cannot buy free delivery.';

CREATE INDEX idx_shipping_rates_lookup ON shipping_rates (country_code, method);
