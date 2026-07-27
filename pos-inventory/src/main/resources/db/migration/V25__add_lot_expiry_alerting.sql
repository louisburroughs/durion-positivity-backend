-- odoo-parity E3 (#1047, spec §6 E3): lot expiry, alert window, and quarantine/recall.
--
-- expiration_date already exists on inventory_lot since E1 (V18). This migration adds the
-- alert-window date and the emit-once bookkeeping columns the daily LotExpiryScheduler uses
-- so a re-run never re-emits LotExpiryAlertV1 for a lot whose transition was already alerted.
--
-- alert_date       : the date the lot enters its "expiring soon" window (optional; when null the
--                    lot only ever raises an EXPIRED alert, never an EXPIRING one).
-- expiring_alerted_at : stamped when the EXPIRING alert fired; NULL until then.
-- expired_alerted_at  : stamped when the EXPIRED alert fired; NULL until then.
ALTER TABLE inventory_lot ADD COLUMN alert_date date;
ALTER TABLE inventory_lot ADD COLUMN expiring_alerted_at timestamp(6) with time zone;
ALTER TABLE inventory_lot ADD COLUMN expired_alerted_at timestamp(6) with time zone;

-- Partial index over the ACTIVE lots the daily scan walks: only dated, not-yet-fully-alerted
-- lots ever need visiting.
CREATE INDEX idx_inventory_lot_expiry_scan
    ON inventory_lot (expiration_date)
    WHERE status = 'ACTIVE' AND expiration_date IS NOT NULL;
