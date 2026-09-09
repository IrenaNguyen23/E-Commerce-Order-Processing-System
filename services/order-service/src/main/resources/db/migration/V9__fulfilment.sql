-- =====================================================================================
-- C3 -- shipments.
--
-- The design decision worth reading: THIS IS NOT PART OF orders.status.
--
-- OrderStatus is the saga's state machine, and every transition in it is guarded so a late
-- or duplicated saga reply cannot move an order backwards. That guard is the whole reason
-- redelivery is safe.
--
-- Fulfilment is a different lifecycle on a different clock. It starts after the saga has
-- finished, it is driven by people and carriers rather than messages, and it legitimately
-- goes backwards -- a parcel marked delivered that turns out to have been signed for at the
-- wrong building. Folding the two together would mean either loosening the saga's guard, or
-- refusing corrections that warehouse staff genuinely need to make.
--
-- So an order is COMPLETED and its shipment is IN_TRANSIT. Two facts, both true.
-- =====================================================================================

CREATE TABLE shipments (
    id              UUID         NOT NULL,
    order_id        UUID         NOT NULL,
    order_number    VARCHAR(32)  NOT NULL,
    user_id         UUID         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    carrier         VARCHAR(100),
    tracking_number VARCHAR(100),
    tracking_url    VARCHAR(500),
    destination     VARCHAR(500) NOT NULL,
    promised_by     TIMESTAMPTZ,
    dispatched_at   TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_shipments PRIMARY KEY (id),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_shipments_status CHECK (status IN
        ('PENDING', 'PICKING', 'DISPATCHED', 'IN_TRANSIT', 'ATTEMPTED',
         'DELIVERED', 'RETURNED', 'CANCELLED'))
);

COMMENT ON TABLE shipments IS
    'A parcel. Deliberately separate from orders.status: see the header of this migration.';
COMMENT ON COLUMN shipments.tracking_url IS
    'Stored rather than built from the carrier name at render time. Carriers change their URL
     formats, and a link constructed from a pattern in code breaks every historical shipment
     the day they do.';
COMMENT ON COLUMN shipments.promised_by IS
    'From the delivery window quoted at checkout, so a late parcel is late against what was
     actually promised rather than a target invented afterwards.';
COMMENT ON COLUMN shipments.destination IS
    'Copied from the order, which itself copied it from the customer. A shipping label has to
     keep saying what it said when it was printed.';

CREATE INDEX idx_shipments_order ON shipments (order_id);
CREATE INDEX idx_shipments_status ON shipments (status, created_at);
CREATE INDEX idx_shipments_tracking ON shipments (tracking_number);

-- ---------------------------------------------------------------- history
--
-- Append-only. "When did this ship?" and "the carrier says they tried on Tuesday, why does
-- the site say delivered on Monday?" are the two questions support actually receives, and a
-- status column holding only the latest value answers neither.

CREATE TABLE shipment_events (
    id          UUID         NOT NULL,
    shipment_id UUID         NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    note        VARCHAR(500),
    location    VARCHAR(150),
    recorded_by UUID,
    recorded_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_shipment_events PRIMARY KEY (id),
    CONSTRAINT fk_shipment_events_shipment FOREIGN KEY (shipment_id)
        REFERENCES shipments (id) ON DELETE CASCADE
);

COMMENT ON COLUMN shipment_events.recorded_by IS
    'The operator who recorded it, or NULL when it came from a carrier. That distinction is
     what matters when a customer and a courier disagree.';

CREATE INDEX idx_shipment_events_shipment ON shipment_events (shipment_id, recorded_at);
