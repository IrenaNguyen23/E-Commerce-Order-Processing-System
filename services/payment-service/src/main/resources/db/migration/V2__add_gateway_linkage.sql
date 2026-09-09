-- =====================================================================================
-- Real acquirers answer asynchronously.
--
-- The simulated one decides on the spot, so a charge and its saga reply were always written
-- in the same transaction and the reply could be built from the command that was in scope.
-- A real provider frequently says "processing" and tells us the outcome minutes later, over a
-- webhook that arrives with no command anywhere near it.
--
-- So the payment row now carries the two things a reply needs — which saga it belongs to and
-- which command it answers — and the provider's own handle, so a webhook can find it and a
-- stuck payment can be reconciled against the acquirer.
-- =====================================================================================

ALTER TABLE payments ADD COLUMN saga_id           UUID;
ALTER TABLE payments ADD COLUMN command_id        UUID;
ALTER TABLE payments ADD COLUMN gateway_reference VARCHAR(128);

COMMENT ON COLUMN payments.saga_id IS
    'Saga this payment belongs to. Without it a webhook cannot build a reply the orchestrator accepts.';
COMMENT ON COLUMN payments.command_id IS
    'eventId of the PROCESS_PAYMENT command, echoed as causationId so the step log closes correctly.';
COMMENT ON COLUMN payments.gateway_reference IS
    'The provider handle, e.g. a Stripe PaymentIntent id. The webhook lookup key, and what reconciliation asks about.';

-- The webhook path: an event arrives naming a provider reference, and it maps to exactly one
-- payment or to none. Unique because two payments sharing a reference would let one webhook
-- settle the wrong order.
CREATE UNIQUE INDEX uk_payments_gateway_reference
    ON payments (gateway_reference)
    WHERE gateway_reference IS NOT NULL;

-- Finding payments that are still waiting on an acquirer, for reconciliation.
CREATE INDEX idx_payments_pending ON payments (status, created_at) WHERE status = 'PENDING';
