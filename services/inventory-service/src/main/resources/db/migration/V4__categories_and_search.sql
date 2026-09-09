-- =====================================================================================
-- C10 and C12 -- catalogue sections, and search that can use an index.
--
-- Two changes that belong together, because the second one indexes the first.
-- =====================================================================================

-- ---------------------------------------------------------------- sections
CREATE TABLE categories (
    id          UUID         NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    parent_id   UUID,
    position    INTEGER      NOT NULL DEFAULT 0,
    image_url   VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_slug UNIQUE (slug),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT ck_categories_not_self CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_categories_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

COMMENT ON TABLE categories IS
    'Sections of the catalogue. Replaces a free-text column, which in practice held four
     spellings of every category and nobody noticed until a filter returned two thirds of
     the range.';
COMMENT ON COLUMN categories.slug IS
    'Stable identity. URLs, saved filters and the tax rate card key on this. Never edited:
     renaming it would silently change the tax charged on everything inside.';

CREATE INDEX idx_categories_parent ON categories (parent_id);

-- ---------------------------------------------------------------- backfill from the strings
--
-- Every distinct category string becomes a section, keeping the products where they were.
-- Done in SQL rather than by hand so a deployment with real data lands in the same place
-- as a fresh one.

INSERT INTO categories (id, slug, name, description, parent_id, position, active,
                        created_at, updated_at, version)
SELECT
    gen_random_uuid(),
    LOWER(REGEXP_REPLACE(REGEXP_REPLACE(category, '[^a-zA-Z0-9]+', '-', 'g'), '(^-|-$)', '', 'g')),
    -- Title case from a SHOUTED string: "COMPUTERS" reads as a database dump, "Computers"
    -- reads as a shop.
    INITCAP(REPLACE(category, '_', ' ')),
    NULL, NULL, 0, TRUE, NOW(), NOW(), 0
FROM (SELECT DISTINCT category FROM products WHERE category IS NOT NULL AND category <> '') AS c;

ALTER TABLE products ADD COLUMN category_id UUID;

UPDATE products p
   SET category_id = c.id
  FROM categories c
 WHERE c.slug = LOWER(REGEXP_REPLACE(
                   REGEXP_REPLACE(p.category, '[^a-zA-Z0-9]+', '-', 'g'), '(^-|-$)', '', 'g'));

ALTER TABLE products
    ADD CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id);

DROP INDEX IF EXISTS idx_products_category;
CREATE INDEX idx_products_category ON products (category_id);

-- The string column goes. Keeping both would mean two answers to "what category is this",
-- and they would disagree within a month.
ALTER TABLE products DROP COLUMN category;

-- ---------------------------------------------------------------- search
--
-- Replaces LIKE '%term%'. Three things were wrong with that, worst last:
--   * a leading wildcard cannot use an index, so every keystroke was a full table scan;
--   * it matched substrings, not words, so "art" matched "cartridge";
--   * it had no notion of a word's root, so "headphones" found nothing filed as
--     "headphone".
--
-- Weighted, so that what a product is CALLED outranks what its description mentions.
-- Weight A: name and SKU. B: description. C: the category it sits in.

ALTER TABLE products ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        SETWEIGHT(TO_TSVECTOR('simple', COALESCE(name, '')), 'A') ||
        SETWEIGHT(TO_TSVECTOR('simple', COALESCE(sku, '')), 'A') ||
        SETWEIGHT(TO_TSVECTOR('simple', COALESCE(description, '')), 'B')
    ) STORED;

COMMENT ON COLUMN products.search_vector IS
    'Generated, so it can never drift from the row it describes -- a trigger-maintained
     column can, after a bulk UPDATE that forgets to fire it.

     The dictionary is ''simple'' rather than ''english'': the catalogue is not
     single-language, and stemming Dutch or German product names with English rules
     produces worse matches than not stemming at all. The cost is that plurals do not
     match their singulars; the prefix search in ProductService covers the common case.';

CREATE INDEX idx_products_search ON products USING GIN (search_vector);

-- Category is not in the vector above, deliberately: a generated column cannot reference
-- another table, and a trigger that denormalised the category name into products would be
-- one more thing to keep in step. Filtering by category is a separate, indexed predicate.

-- ---------------------------------------------------------------- C15: uploaded images
--
-- The bytes are in Postgres. That is a decision with a shelf life, not an oversight:
-- object storage behind a CDN is where product images belong at any real volume, and no
-- such infrastructure has been chosen for this deployment. The alternative to this table
-- was leaving image upload unbuilt.
--
-- What it costs, stated rather than discovered:
--   * every image served is a request this service handles, where a CDN would absorb it;
--   * database dumps now carry image bytes;
--   * nothing resizes, so a phone photograph is served at full size to a thumbnail.
--
-- ProductImageService is the seam. Moving to S3 is one new implementation plus a job that
-- copies these rows out.

CREATE TABLE product_images (
    id           UUID         NOT NULL,
    product_id   UUID         NOT NULL,
    data         BYTEA        NOT NULL,
    content_type VARCHAR(50)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    file_name    VARCHAR(255),
    alt_text     VARCHAR(255),
    position     INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_product_images PRIMARY KEY (id),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id)
        REFERENCES products (id) ON DELETE CASCADE,

    -- Only what the service will actually store, and only what it verified from the bytes.
    -- SVG is absent on purpose: it can contain script, and serving one inline from this
    -- origin is a cross-site scripting vector wearing a picture's clothes.
    CONSTRAINT ck_product_images_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_product_images_size CHECK (size_bytes > 0),
    CONSTRAINT ck_product_images_position CHECK (position >= 0)
);

COMMENT ON TABLE product_images IS
    'Uploaded product photographs. Bytes live here until object storage is chosen; see
     ProductImage for what that costs.';
COMMENT ON COLUMN product_images.content_type IS
    'Determined by inspecting the bytes, never taken from the upload''s Content-Type header.
     Storing a client-supplied type is how an HTML document gets served back as an image
     from this service''s own origin.';
COMMENT ON COLUMN product_images.position IS
    'Gallery order. Position 0 is the tile shown on a listing.';

CREATE INDEX idx_product_images_product ON product_images (product_id, position);

-- ---------------------------------------------------------------- C6: reviews
--
-- The rating lives on the product as well as in the reviews, and that duplication is
-- deliberate. A listing page shows stars on every tile; averaging reviews at read time is
-- one aggregate query per tile on the busiest page in the shop. The copy is recomputed --
-- freshly, not incrementally -- whenever a review is published, rejected or removed.
--
-- An incrementally maintained average is correct until one update is missed, and then it is
-- quietly wrong forever with nothing to compare it against.

ALTER TABLE products
    ADD COLUMN rating_average NUMERIC(3, 2),
    ADD COLUMN rating_count   INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN products.rating_average IS
    'Average of published reviews. NULL when there are none -- "no reviews yet" and
     "reviewed, and terrible" are different things, and a zero renders as the second.';

CREATE TABLE product_reviews (
    id                UUID         NOT NULL,
    product_id        UUID         NOT NULL,
    user_id           UUID         NOT NULL,
    author_name       VARCHAR(100) NOT NULL,
    rating            INTEGER      NOT NULL,
    title             VARCHAR(150),
    body              VARCHAR(4000),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    verified_purchase BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    moderated_by      UUID,
    moderated_at      TIMESTAMPTZ,
    moderation_note   VARCHAR(255),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_reviews PRIMARY KEY (id),
    CONSTRAINT fk_product_reviews_product FOREIGN KEY (product_id)
        REFERENCES products (id) ON DELETE CASCADE,

    -- One per customer per product. Without it, a product page is whoever is most persistent.
    CONSTRAINT uk_product_reviews_author UNIQUE (product_id, user_id),
    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_product_reviews_status CHECK (status IN ('PENDING', 'PUBLISHED', 'REJECTED'))
);

COMMENT ON COLUMN product_reviews.author_name IS
    'Copied at submission, like a product name on an order line. Also keeps this service out
     of the accounts database, which it has no business reading to render "Ada L.".';
COMMENT ON COLUMN product_reviews.verified_purchase IS
    'Decided once, when the review was written. A review that silently becomes verified
     months later, because its author eventually bought one, is a badge that means nothing.';

CREATE INDEX idx_reviews_product ON product_reviews (product_id, status, created_at);
CREATE INDEX idx_reviews_author ON product_reviews (user_id);

-- ---------------------------------------------------------------- who bought what
--
-- Built by listening to order.completed rather than by asking Order Service. Order depends
-- on Inventory; the reverse dependency would be new, and it would make writing a review fail
-- when Order Service is down.
--
-- This is the clearest illustration of why the domain events survived the move from
-- choreography to orchestration: the saga commands named participants, but order.completed
-- is an announcement, and this subscriber required no change to the flow whatsoever.

CREATE TABLE verified_purchases (
    id           UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    product_id   UUID        NOT NULL,
    purchased_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_verified_purchases PRIMARY KEY (id),
    CONSTRAINT uk_verified_purchases UNIQUE (user_id, product_id)
);

COMMENT ON TABLE verified_purchases IS
    'That a customer bought a product. Not how many, not for how much, not when they last did
     -- anything more would be a copy of Order Service''s data drifting out of step with it.';
