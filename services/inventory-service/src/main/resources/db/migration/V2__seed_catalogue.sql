-- =====================================================================================
-- Demo catalogue.
--
-- Seeded with fixed UUIDs so `docker compose up` gives a working system that the README
-- walkthrough and the end-to-end saga tests can address by id. Safe to re-run: every insert
-- is guarded by ON CONFLICT DO NOTHING.
-- =====================================================================================

INSERT INTO products (id, sku, name, description, category, price, currency, image_url, active,
                      created_at, updated_at, version)
VALUES
    ('11111111-1111-1111-1111-111111111101', 'CF-LAPTOP-001', 'CommerceFlow Developer Laptop 14',
     '14 inch, 32 GB RAM, 1 TB NVMe. Built for long compile runs.', 'COMPUTERS',
     1899.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0),

    ('11111111-1111-1111-1111-111111111102', 'CF-PHONE-001', 'CommerceFlow Phone X',
     '6.1 inch OLED, 256 GB, dual SIM.', 'PHONES',
     949.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0),

    ('11111111-1111-1111-1111-111111111103', 'CF-HEADSET-001', 'CommerceFlow Noise Cancelling Headset',
     'Over-ear, 40 hour battery, USB-C.', 'AUDIO',
     279.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0),

    ('11111111-1111-1111-1111-111111111104', 'CF-KEYBOARD-001', 'CommerceFlow Mechanical Keyboard',
     'Tenkeyless, hot-swappable switches, ISO layout.', 'ACCESSORIES',
     149.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0),

    ('11111111-1111-1111-1111-111111111105', 'CF-MOUSE-001', 'CommerceFlow Ergonomic Mouse',
     'Vertical grip, 8 programmable buttons.', 'ACCESSORIES',
     79.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0),

    ('11111111-1111-1111-1111-111111111106', 'CF-MONITOR-001', 'CommerceFlow 27 inch 4K Monitor',
     '27 inch, 3840x2160, USB-C power delivery.', 'COMPUTERS',
     629.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0),

    -- Deliberately scarce: makes the out-of-stock compensation path easy to demonstrate.
    ('11111111-1111-1111-1111-111111111107', 'CF-LIMITED-001', 'CommerceFlow Limited Edition Dock',
     'Only a handful in stock; used to exercise the inventory.failed saga branch.', 'ACCESSORIES',
     199.0000, 'EUR', NULL, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO inventory_items (product_id, sku, available_quantity, reserved_quantity, reorder_level,
                             updated_at, version)
VALUES
    ('11111111-1111-1111-1111-111111111101', 'CF-LAPTOP-001',   50, 0, 10, NOW(), 0),
    ('11111111-1111-1111-1111-111111111102', 'CF-PHONE-001',   120, 0, 25, NOW(), 0),
    ('11111111-1111-1111-1111-111111111103', 'CF-HEADSET-001',  80, 0, 15, NOW(), 0),
    ('11111111-1111-1111-1111-111111111104', 'CF-KEYBOARD-001', 60, 0, 10, NOW(), 0),
    ('11111111-1111-1111-1111-111111111105', 'CF-MOUSE-001',    90, 0, 20, NOW(), 0),
    ('11111111-1111-1111-1111-111111111106', 'CF-MONITOR-001',  35, 0,  5, NOW(), 0),
    ('11111111-1111-1111-1111-111111111107', 'CF-LIMITED-001',   2, 0,  0, NOW(), 0)
ON CONFLICT (product_id) DO NOTHING;
