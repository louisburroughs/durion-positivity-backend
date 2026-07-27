-- odoo-parity C3 (#1039, spec C4): short-close metadata on the transfer order.
--
-- Short-closing a DISPATCHED/PARTIALLY_RECEIVED order resolves the undelivered in-transit
-- remainder with one whole-order disposition (v1 simplification — no per-line dispositions):
--   LOST_IN_TRANSIT    — constructive TRANSFER_IN at the destination + paired SCRAP_OUT
--                        (reason LOST); global on-hand drops by the remainder.
--   RETURNED_TO_SOURCE — constructive TRANSFER_IN at the destination + TRANSFER_OUT back +
--                        TRANSFER_IN at the source; source on-hand is restored.
-- Both shapes zero the destination in-transit balance purely through existing ledger rules
-- (TRANSFER_OUT.to_location_id contributes in-transit at the destination key; TRANSFER_IN
-- cancels at its own key), so drift verification and rebuild need no changes. Reason and
-- notes are mandatory on the API; the columns stay nullable because every pre-C3 row and
-- every non-short-closed row carries none. The SHORT_CLOSED status value already exists in
-- transfer_order_status_check (declared by V15).
ALTER TABLE transfer_order
    ADD COLUMN short_close_disposition character varying(30),
    ADD COLUMN short_close_reason character varying(100),
    ADD COLUMN short_close_notes character varying(2000),
    ADD COLUMN short_closed_by character varying(255),
    ADD COLUMN short_closed_at timestamp(6) with time zone;

ALTER TABLE transfer_order
    ADD CONSTRAINT transfer_order_short_close_disposition_check
        CHECK (short_close_disposition IS NULL
               OR short_close_disposition IN ('LOST_IN_TRANSIT', 'RETURNED_TO_SOURCE'));
