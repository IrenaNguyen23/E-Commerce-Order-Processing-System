-- =====================================================================================
-- C14 -- stock has a location.
--
-- A single global stock number is a lie the moment a shop has two buildings. It says twelve
-- units are available; six are in Amsterdam and six are in Milan, and an order for eight
-- either ships in two parcels from two countries or cannot ship at all -- and nothing in
-- the system knew that until somebody in a warehouse found out.
--
-- THE IMPORTANT PART: stock_levels is the truth. inventory_items keeps its quantity columns
-- as a SUMMARY, recomputed from stock_levels in the same transaction as every movement. Two
-- tables holding stock numbers is exactly the shape that oversells, so the rule is written
-- down here and enforced in one place in the code: nothing reserves against the summary and
-- nothing writes it directly.
--
-- The summary exists because a product listing shows availability on every tile, and summing
-- a warehouse table per tile is the same problem as averaging reviews per tile.
-- =====================================================================================

CREATE TABLE warehouses (
    id           UUID         NOT NULL,
    code         VARCHAR(20)  NOT NULL,
    name         VARCHAR(150) NOT NULL,
    country_code CHAR(2)      NOT NULL,
    city         VARCHAR(100),
    priority     INTEGER      NOT NULL DEFAULT 100,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_warehouses PRIMARY KEY (id),
    CONSTRAINT uk_warehouses_code UNIQUE (code),
    CONSTRAINT ck_warehouses_code CHECK (code = UPPER(code)),
    CONSTRAINT ck_warehouses_country CHECK (country_code = UPPER(country_code))
);

COMMENT ON COLUMN warehouses.priority IS
    'Lower goes first when more than one building could fill a line. An explicit number
     rather than a computed distance: which building something ships from depends on carrier
     contracts and what a shop is clearing, neither of which a coordinate expresses.';
COMMENT ON COLUMN warehouses.active IS
    'Whether NEW orders may be allocated from here. Turning it off leaves existing
     reservations alone -- during a stock take a building stops taking work without
     abandoning what it already promised to fill.';

CREATE INDEX idx_warehouses_active ON warehouses (active, priority);

CREATE TABLE stock_levels (
    id                 UUID        NOT NULL,
    warehouse_id       UUID        NOT NULL,
    product_id         UUID        NOT NULL,
    available_quantity INTEGER     NOT NULL DEFAULT 0,
    reserved_quantity  INTEGER     NOT NULL DEFAULT 0,
    reorder_level      INTEGER     NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_levels PRIMARY KEY (id),
    CONSTRAINT fk_stock_levels_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
    CONSTRAINT fk_stock_levels_product FOREIGN KEY (product_id)
        REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT uk_stock_levels UNIQUE (warehouse_id, product_id),
    CONSTRAINT ck_stock_levels_available CHECK (available_quantity >= 0),
    CONSTRAINT ck_stock_levels_reserved CHECK (reserved_quantity >= 0)
);

COMMENT ON TABLE stock_levels IS
    'How much of one product is in one building. THE authoritative stock figure.
     inventory_items summarises these rows; nothing reserves against the summary.';

CREATE INDEX idx_stock_levels_product ON stock_levels (product_id);
CREATE INDEX idx_stock_levels_warehouse ON stock_levels (warehouse_id);

-- ---------------------------------------------------------------- the reservation locates itself
ALTER TABLE reservation_items ADD COLUMN warehouse_id UUID;

ALTER TABLE reservation_items
    ADD CONSTRAINT fk_reservation_items_warehouse
    FOREIGN KEY (warehouse_id) REFERENCES warehouses (id);

COMMENT ON COLUMN reservation_items.warehouse_id IS
    'Which building these units are held in. Releasing, confirming and restocking all act on
     THIS building -- working one out later would be a guess, wrong exactly when stock has
     moved between buildings since.

     NULL on rows written before this migration. Those units went into the single warehouse
     created below, and the code handles that case explicitly rather than assuming.';

CREATE INDEX idx_reservation_items_warehouse ON reservation_items (warehouse_id);

-- ---------------------------------------------------------------- backfill
--
-- One building, holding everything that exists today. Done in SQL so a deployment with real
-- data lands where a fresh one does, and so no stock is unaccounted for between the two
-- models even for an instant.

INSERT INTO warehouses (id, code, name, country_code, city, priority, active,
                        created_at, updated_at, version)
VALUES ('d0000000-0000-4000-8000-000000000001', 'MAIN', 'Main warehouse', 'NL', 'Amsterdam',
        10, TRUE, NOW(), NOW(), 0);

INSERT INTO stock_levels (id, warehouse_id, product_id, available_quantity, reserved_quantity,
                          reorder_level, updated_at, version)
SELECT gen_random_uuid(),
       'd0000000-0000-4000-8000-000000000001',
       i.product_id,
       i.available_quantity,
       i.reserved_quantity,
       i.reorder_level,
       NOW(),
       0
FROM inventory_items i;

-- Existing holds all came out of that one building.
UPDATE reservation_items SET warehouse_id = 'd0000000-0000-4000-8000-000000000001'
 WHERE warehouse_id IS NULL;

COMMENT ON COLUMN inventory_items.available_quantity IS
    'SUMMARY of stock_levels.available_quantity for this product, recomputed in the same
     transaction as every movement. Never written directly, never reserved against.';
COMMENT ON COLUMN inventory_items.reserved_quantity IS
    'SUMMARY of stock_levels.reserved_quantity. See available_quantity.';
