-- =====================================================================================
-- Cancelling an order that already got somewhere.
--
-- Undoing stock is two different operations and the saga has to remember which one it needs.
-- An order still at PAID has its stock on hold: give the hold up. One that reached COMPLETED
-- had its stock written off as sold: put it back on the shelf. Sending the wrong command either
-- leaves the units held forever or credits stock that was never deducted, and neither raises an
-- error anywhere.
--
-- The order's own status cannot answer this later, because cancelling sets it to CANCELLED and
-- the previous value is gone. So the decision is taken once, at cancellation, and recorded.
-- =====================================================================================

ALTER TABLE saga_instance ADD COLUMN stock_undo VARCHAR(16);

ALTER TABLE saga_instance
    ADD CONSTRAINT ck_saga_stock_undo
    CHECK (stock_undo IS NULL OR stock_undo IN ('RELEASE', 'RESTOCK'));

COMMENT ON COLUMN saga_instance.stock_undo IS
    'How this cancellation must return stock: RELEASE a hold, or RESTOCK goods already sold';

-- The two compensation steps a cancellation can reach.
ALTER TABLE saga_instance DROP CONSTRAINT ck_saga_instance_step;

ALTER TABLE saga_instance
    ADD CONSTRAINT ck_saga_instance_step
    CHECK (current_step IS NULL OR current_step IN (
        'RESERVE_INVENTORY', 'PROCESS_PAYMENT', 'CONFIRM_INVENTORY',
        'RELEASE_INVENTORY', 'REFUND_PAYMENT', 'RESTOCK_INVENTORY', 'NOTIFY_CUSTOMER'));
