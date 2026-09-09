-- =====================================================================================
-- Completing the order snapshot.
--
-- An order is a historical record of what a customer agreed to buy and what they agreed to pay.
-- Anything it reads back from the live catalogue is a claim that can quietly change under it:
-- rename a product and last year's invoice renames itself; swap an image and an order shows a
-- picture the customer never saw.
--
-- Name, SKU and unit price were already snapshotted. These columns finish the job.
--
--   product_category / product_image_url   what the customer was actually looking at
--   list_price / discount_amount           how the charged price was arrived at
--   subtotal_amount / discount_total       the same, at order level
--
-- The discount columns are structurally zero today because there is no promotion engine yet.
-- They exist now so that when one arrives it records *why* a price was what it was, in the same
-- row as the price — rather than leaving old orders with a number nobody can explain.
-- =====================================================================================

ALTER TABLE order_items ADD COLUMN product_category  VARCHAR(100);
ALTER TABLE order_items ADD COLUMN product_image_url VARCHAR(500);
ALTER TABLE order_items ADD COLUMN list_price        NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN discount_amount   NUMERIC(19, 4) NOT NULL DEFAULT 0;

-- Existing lines were sold at list price with no discount, because no discount could exist.
-- Leaving list_price at its zero default would make every historical line look like a giveaway.
UPDATE order_items SET list_price = unit_price WHERE list_price = 0;

ALTER TABLE order_items
    ADD CONSTRAINT ck_order_items_list_price CHECK (list_price >= 0),
    ADD CONSTRAINT ck_order_items_discount CHECK (discount_amount >= 0);

COMMENT ON COLUMN order_items.product_category IS
    'Category at order time. The catalogue may have reorganised since.';
COMMENT ON COLUMN order_items.product_image_url IS
    'The image the customer saw. Not looked up live: products get re-shot and replaced.';
COMMENT ON COLUMN order_items.list_price IS
    'Catalogue price at order time, before any discount';
COMMENT ON COLUMN order_items.discount_amount IS
    'Per unit reduction. Always 0 until a promotion engine exists.';
COMMENT ON COLUMN order_items.unit_price IS
    'What was actually charged per unit: list_price - discount_amount';

ALTER TABLE orders ADD COLUMN subtotal_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN discount_total  NUMERIC(19, 4) NOT NULL DEFAULT 0;

UPDATE orders SET subtotal_amount = total_amount WHERE subtotal_amount = 0;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_subtotal CHECK (subtotal_amount >= 0),
    ADD CONSTRAINT ck_orders_discount CHECK (discount_total >= 0);

COMMENT ON COLUMN orders.subtotal_amount IS
    'Sum of the lines at list price, before any discount';
COMMENT ON COLUMN orders.discount_total IS
    'Total reduction. Always 0 until a promotion engine exists.';
COMMENT ON COLUMN orders.total_amount IS
    'What the customer was charged: subtotal_amount - discount_total';

-- =====================================================================================
-- Read model. Derived data, so it is rebuilt rather than backfilled with care: items_json is
-- regenerated from the aggregate by OrderProjectionService.rebuild.
-- =====================================================================================

ALTER TABLE order_read_model ADD COLUMN subtotal_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE order_read_model ADD COLUMN discount_total  NUMERIC(19, 4) NOT NULL DEFAULT 0;

UPDATE order_read_model SET subtotal_amount = total_amount WHERE subtotal_amount = 0;
