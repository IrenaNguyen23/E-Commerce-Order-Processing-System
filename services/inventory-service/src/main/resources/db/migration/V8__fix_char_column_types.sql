-- Align warehouses.country_code with the JPA entity mapping (java.lang.String -> VARCHAR).
-- Same root cause as auth-service V7: V5 used CHAR(2) (Postgres reports this as bpchar).

ALTER TABLE warehouses
    ALTER COLUMN country_code TYPE VARCHAR(2) USING country_code::VARCHAR(2);