-- Align user_addresses.country_code with the JPA entity mapping (java.lang.String -> VARCHAR).
-- The original migration (V3) used CHAR(2) (Postgres reports this as bpchar), which Hibernate's
-- schema validator rejects because the entity has no explicit @JdbcType override and defaults
-- to VARCHAR for a String field. VARCHAR(2) is functionally equivalent here (fixed 2-char ISO
-- country code) and matches the entity without needing a Hibernate-side workaround.

ALTER TABLE user_addresses
    ALTER COLUMN country_code TYPE VARCHAR(2) USING country_code::VARCHAR(2);