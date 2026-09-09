-- =====================================================================================
-- Opening rate cards.
--
-- Seeded rather than left empty so that a fresh `docker compose up` produces a checkout
-- that quotes a real total. These are ordinary rows: an operator changes them with an
-- UPDATE, and no deployment is involved.
--
-- Rates are real published figures for the countries listed, current at the time of
-- writing. They are seed data, not tax advice, and anyone trading for money should
-- confirm them against their own accountant -- which is exactly why they live in a table.
-- =====================================================================================

-- ---------------------------------------------------------------- tax
--
-- A NULL category is the country's standard rate. Category rows exist only where the
-- country actually applies a reduced rate to something this shop sells; inventing a
-- reduced rate is as wrong as missing one.

INSERT INTO tax_rates (id, country_code, category, name, rate, updated_at) VALUES
    ('a1000000-0000-4000-8000-000000000001', 'NL', NULL, 'BTW', 0.2100, NOW()),
    ('a1000000-0000-4000-8000-000000000002', 'DE', NULL, 'MwSt', 0.1900, NOW()),
    ('a1000000-0000-4000-8000-000000000003', 'FR', NULL, 'TVA', 0.2000, NOW()),
    ('a1000000-0000-4000-8000-000000000004', 'BE', NULL, 'BTW', 0.2100, NOW()),
    ('a1000000-0000-4000-8000-000000000005', 'ES', NULL, 'IVA', 0.2100, NOW()),
    ('a1000000-0000-4000-8000-000000000006', 'IT', NULL, 'IVA', 0.2200, NOW()),
    ('a1000000-0000-4000-8000-000000000007', 'IE', NULL, 'VAT', 0.2300, NOW()),
    ('a1000000-0000-4000-8000-000000000008', 'GB', NULL, 'VAT', 0.2000, NOW());

-- Not listed above: every other destination. That is a decision, not an omission -- a
-- country with no rows attracts no tax here, which is visible and checkable. Defaulting
-- to some rate would invent a tax nobody set.

-- ---------------------------------------------------------------- delivery
--
-- Free above EUR 75 for standard delivery inside the EU. Express never becomes free: the
-- cost of putting a parcel on a plane does not fall away because the basket was large.

INSERT INTO shipping_rates
    (id, country_code, method, currency, base_amount, per_item_amount, free_above,
     min_days, max_days, active, updated_at)
VALUES
    -- Home market
    ('b1000000-0000-4000-8000-000000000001', 'NL', 'STANDARD', 'EUR',  4.9500, 0.0000, 75.0000, 1, 2, TRUE, NOW()),
    ('b1000000-0000-4000-8000-000000000002', 'NL', 'EXPRESS',  'EUR', 12.5000, 0.5000, NULL,    1, 1, TRUE, NOW()),

    -- Neighbours
    ('b1000000-0000-4000-8000-000000000003', 'BE', 'STANDARD', 'EUR',  6.9500, 0.0000, 75.0000, 2, 3, TRUE, NOW()),
    ('b1000000-0000-4000-8000-000000000004', 'DE', 'STANDARD', 'EUR',  6.9500, 0.0000, 75.0000, 2, 3, TRUE, NOW()),
    ('b1000000-0000-4000-8000-000000000005', 'DE', 'EXPRESS',  'EUR', 16.5000, 0.7500, NULL,    1, 2, TRUE, NOW()),
    ('b1000000-0000-4000-8000-000000000006', 'FR', 'STANDARD', 'EUR',  8.9500, 0.0000, 95.0000, 3, 5, TRUE, NOW()),

    -- Rest of the world. Not a rejection: a shop that cannot price delivery to Chile
    -- should say what it costs to ship to Chile, not fail after the customer has typed
    -- in everything.
    ('b1000000-0000-4000-8000-000000000099', '*',  'STANDARD', 'EUR', 19.9500, 1.5000, NULL,    7, 21, TRUE, NOW());

-- PICKUP has no row anywhere, on purpose. It is always free and always same-day, and a
-- "free" rate row is a row somebody can later edit into a charge for walking to a shop.
