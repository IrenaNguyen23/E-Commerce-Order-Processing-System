-- Align every CHAR(n) column introduced in V5/V7/V12 with the JPA entity mappings
-- (java.lang.String -> VARCHAR by default). Same root cause as auth-service V7 and
-- inventory-service V8. Consolidated into one migration since order-service accumulated
-- the pattern across three earlier migrations.

ALTER TABLE orders
    ALTER COLUMN shipping_country TYPE VARCHAR(2) USING shipping_country::VARCHAR(2);

ALTER TABLE order_read_model
    ALTER COLUMN shipping_country TYPE VARCHAR(2) USING shipping_country::VARCHAR(2);

ALTER TABLE tax_rates
    ALTER COLUMN country_code TYPE VARCHAR(2) USING country_code::VARCHAR(2);

ALTER TABLE shipping_rates
    ALTER COLUMN country_code TYPE VARCHAR(2) USING country_code::VARCHAR(2),
    ALTER COLUMN currency     TYPE VARCHAR(3) USING currency::VARCHAR(3);

ALTER TABLE coupons
    ALTER COLUMN currency TYPE VARCHAR(3) USING currency::VARCHAR(3);

ALTER TABLE coupon_redemptions
    ALTER COLUMN currency TYPE VARCHAR(3) USING currency::VARCHAR(3);

ALTER TABLE return_requests
    ALTER COLUMN currency TYPE VARCHAR(3) USING currency::VARCHAR(3);