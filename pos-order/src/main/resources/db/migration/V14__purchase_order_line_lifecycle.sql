-- CAP:550 #2635 (Order ruling on #2598): a purchase-order line carries what was received, returned, corrected and
-- cancelled, so a vendor return, a receipt correction, a replacement shipment or a short-closed remainder leaves the
-- line showing exactly what the vendor still owes. open_quantity_decimal stays the stored, published figure, now
-- derived: open = max(0, ordered - received - returned - cancelled); the order's open balance keeps moving per event
-- (a receipt takes its accrual off, a correction puts it back, a short-close takes the quantity's value off).
-- Quantities are base units (ADR-0055); values are whole minor units of the order's currency (ADR-0067 R-1). All
-- additive with defaults, no lock beyond the catalogue change; existing rows are backfilled from what the open
-- quantity already said.
-- received_quantity is nullable on purpose: null marks a line the figures have not touched yet (adopted from the
-- open quantity on first use, exactly as the backfill below does for every stored row); a zero is a real zero.
ALTER TABLE public.purchase_order_line ADD COLUMN received_quantity numeric(18,6);
ALTER TABLE public.purchase_order_line ADD COLUMN received_accrued_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN returned_quantity numeric(18,6) NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN returned_value_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN cancelled_quantity numeric(18,6) NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN cancelled_value_minor bigint NOT NULL DEFAULT 0;

-- Backfill every row: received = ordered - open where an open quantity was recorded (else 0); its accrual at the
-- line's unit price.
UPDATE public.purchase_order_line
   SET received_quantity = CASE
           WHEN open_quantity_decimal IS NOT NULL THEN GREATEST(0, quantity_decimal - open_quantity_decimal)
           ELSE 0 END,
       received_accrued_minor = CASE
           WHEN open_quantity_decimal IS NOT NULL AND quantity_decimal > 0
               THEN ROUND(line_total_minor * GREATEST(0, quantity_decimal - open_quantity_decimal) / quantity_decimal)
           ELSE 0 END;
