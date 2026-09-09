-- =====================================================================================
-- C8 -- the basket and the wishlist, on the server.
--
-- The one thing worth reading twice: THESE TABLES STORE NO PRICES.
--
-- An order snapshots everything, because an order records what was agreed. A basket is the
-- opposite -- a list of intentions that has to show what things cost now. A price stored
-- here would mean a basket saved on Tuesday quoting Tuesday's price on Friday and the
-- checkout charging Friday's, with the customer watching a number change between two
-- screens and no explanation anywhere.
-- =====================================================================================

CREATE TABLE carts (
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_carts PRIMARY KEY (id),

    -- The customer IS the identity of the basket. Signing in on a second device has to find
    -- the same one, which is the entire reason for moving it off the device.
    CONSTRAINT uk_carts_user UNIQUE (user_id)
);

COMMENT ON TABLE carts IS
    'One basket per customer. Holds no prices: everything a basket page shows is read live
     from the catalogue.';

CREATE TABLE cart_items (
    id         UUID        NOT NULL,
    cart_id    UUID        NOT NULL,
    product_id UUID        NOT NULL,
    quantity   INTEGER     NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_cart_items PRIMARY KEY (id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,

    -- One line per product. Two rows for the same product would display as two lines and
    -- check out as one, or the other way round depending on which code path ran.
    CONSTRAINT uk_cart_items_product UNIQUE (cart_id, product_id),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity > 0)
);

COMMENT ON COLUMN cart_items.added_at IS
    'When it went in. Kept separate from updated_at, which is when the customer last changed
     their mind about how many.';

CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);

-- ---------------------------------------------------------------- wishlist
--
-- A separate table rather than a flag on cart_items, because the two differ in the way that
-- matters: a basket is emptied when an order is placed and a wishlist is not. Modelling
-- "saved for later" as a cart line with a boolean means every basket query has to remember
-- to exclude them, and the first one that forgets charges somebody for something they were
-- only thinking about.

CREATE TABLE wishlist_items (
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    product_id UUID        NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_wishlist_items PRIMARY KEY (id),

    -- Makes "add to wishlist" idempotent at the database, not only in the service. Two taps
    -- can arrive together, and pressing a heart twice means the same as pressing it once.
    CONSTRAINT uk_wishlist_items UNIQUE (user_id, product_id)
);

CREATE INDEX idx_wishlist_user ON wishlist_items (user_id, added_at);
