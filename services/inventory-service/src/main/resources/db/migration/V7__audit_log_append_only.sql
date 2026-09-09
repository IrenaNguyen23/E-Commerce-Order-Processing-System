-- =====================================================================================
-- The audit trail becomes append-only in fact, not only by convention.
--
-- Until now "append only" was a rule the application code followed. Anyone with a database
-- connection could still UPDATE or DELETE a row -- which means the person an entry describes
-- could remove it, and that is the one thing an audit trail must make impossible.
--
-- Two rules, enforced by the database:
--
--   UPDATE  -- refused outright. There is no legitimate reason to amend a recorded fact.
--   DELETE  -- refused for anything younger than 30 days.
--
-- The 30 day floor is what stops somebody tidying away this morning's activity while leaving
-- the retention sweep able to do its job: it deletes entries older than two years, which are
-- comfortably past the floor.
--
-- ONE CONSEQUENCE, STATED PLAINLY: commerceflow.audit.retention must not be set below 30
-- days. Do that and the nightly sweep starts failing against this trigger. An audit retention
-- shorter than a month would be a strange choice anyway, and failing loudly beats a trigger
-- that quietly permits what it was added to prevent.
--
-- What this does NOT stop: a superuser dropping the trigger, or TRUNCATE, which does not fire
-- row triggers. Neither is the threat here. The threat is an operator with ordinary
-- application-level database access editing the record of what they did, and that is closed.
-- =====================================================================================

CREATE OR REPLACE FUNCTION admin_audit_log_append_only() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'admin_audit_log is append-only: entry % cannot be modified', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF OLD.occurred_at > now() - INTERVAL '30 days' THEN
        RAISE EXCEPTION
            'admin_audit_log entry % is younger than 30 days and cannot be deleted', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;

    -- Only a DELETE past the floor reaches here: the retention sweep, doing its job.
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION admin_audit_log_append_only() IS
    'Refuses every UPDATE, and any DELETE of an entry younger than 30 days. See the header of
     the migration that created it.';

CREATE TRIGGER trg_admin_audit_log_append_only
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_append_only();

-- The index the back office reads through.
--
-- The paging is a keyset on (occurred_at, id) descending, so the composite matches the sort
-- exactly and the id tiebreak is resolved in the index rather than by a sort afterwards.
-- idx_audit_occurred stays: it still serves the retention sweep's range delete.
CREATE INDEX idx_audit_occurred_id ON admin_audit_log (occurred_at DESC, id DESC);
