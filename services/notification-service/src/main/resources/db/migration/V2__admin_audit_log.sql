-- =====================================================================================
-- S1 -- what an operator did.
--
-- Local to this service, like outbox_event and processed_event, and for the same reason: a
-- shared audit database would be one table every service writes to synchronously, and
-- therefore one thing whose availability the whole platform depends on for an operation
-- that must never fail.
--
-- The cost is stated rather than hidden: "everything operator X did" is five queries, not
-- one. docs/runbook.md says which database holds what. A single view means a consumer
-- subscribing to an audit topic and building one -- worth doing when somebody needs it.
--
-- APPEND ONLY. Nothing updates or deletes a row here except the retention sweep. An audit
-- trail that can be edited answers a different question from the one it is asked.
-- =====================================================================================

CREATE TABLE admin_audit_log (
    id             UUID         NOT NULL,
    actor_id       UUID,
    actor_email    VARCHAR(255),
    action         VARCHAR(64)  NOT NULL,
    target_type    VARCHAR(32)  NOT NULL,
    target_id      VARCHAR(64),
    summary        VARCHAR(500),
    source_ip      VARCHAR(45),
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_admin_audit_log PRIMARY KEY (id)
);

COMMENT ON TABLE admin_audit_log IS
    'Operator actions. Append-only; only the retention sweep deletes.';
COMMENT ON COLUMN admin_audit_log.actor_id IS
    'NULL for something the system did on a schedule. Nullable rather than a synthetic
     "system" id, because those two cases genuinely differ and a reader should not need to
     know a magic UUID to tell them apart.';
COMMENT ON COLUMN admin_audit_log.actor_email IS
    'Copied at the time, not joined. The account may be renamed or later erased, and this
     line still has to read as a sentence about a person.';
COMMENT ON COLUMN admin_audit_log.summary IS
    'One sentence naming the change. Deliberately NOT a before/after diff: storing the old
     and new values copies personal data into a table with a two-year retention, and an
     erasure request would then have to find and scrub it here too.';

-- actor + time: "what did this person do last Tuesday".
CREATE INDEX idx_audit_actor ON admin_audit_log (actor_id, occurred_at DESC);

-- target: "everything that ever happened to this order" -- the question after an incident.
CREATE INDEX idx_audit_target ON admin_audit_log (target_type, target_id);

-- occurred_at alone, for the retention sweep and for a plain chronological read.
CREATE INDEX idx_audit_occurred ON admin_audit_log (occurred_at);
