-- =====================================================================================
-- Goods coming back after they were sold.
--
-- RELEASED means a hold was given up before anything was written off. RETURNED means stock that
-- had already been deducted and counted as sold has come back — a cancelled order that had
-- completed. Recording both as RELEASED would make the two indistinguishable in every report
-- that asks how much stock was actually shifted.
-- =====================================================================================

ALTER TABLE inventory_reservations DROP CONSTRAINT ck_reservation_status;

ALTER TABLE inventory_reservations
    ADD CONSTRAINT ck_reservation_status
    CHECK (status IN ('RESERVED', 'RELEASED', 'CONFIRMED', 'RETURNED', 'FAILED'));

COMMENT ON COLUMN inventory_reservations.status IS
    'RESERVED holding · RELEASED hold given up · CONFIRMED sold · RETURNED sold then came back · FAILED never held';
