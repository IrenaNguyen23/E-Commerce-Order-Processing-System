-- Align payment_refunds.currency with the JPA entity mapping (java.lang.String -> VARCHAR).
-- Same root cause as auth-service V7, inventory-service V8, order-service V14.

ALTER TABLE payment_refunds
    ALTER COLUMN currency TYPE VARCHAR(3) USING currency::VARCHAR(3);