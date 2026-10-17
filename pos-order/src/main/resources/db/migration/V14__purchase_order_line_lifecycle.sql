-- CAP:550 #2635 (Order ruling on #2598): a purchase-order line carries what was received, returned, corrected and
-- cancelled, so a vendor return, a receipt correction, a replacement shipment or a short-closed remainder leaves the
-- line showing exactly what the vendor still owes. open_quantity_decimal stays the stored, published figure, now
-- derived: open = max(0, ordered - received - returned - cancelled). Quantities are base units (ADR-0055); values
-- are whole minor units of the order's currency (ADR-0067 R-1). All additive with defaults, no lock beyond the
-- catalogue change; existing rows are backfilled from what the open quantity already said.
ALTER TABLE public.purchase_order_line ADD COLUMN received_quantity numeric(18,6) NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN received_accrued_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN returned_quantity numeric(18,6) NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN returned_value_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN cancelled_quantity numeric(18,6) NOT NULL DEFAULT 0;
ALTER TABLE public.purchase_order_line ADD COLUMN cancelled_value_minor bigint NOT NULL DEFAULT 0;

-- Receipts that named no line of the order moved only the balance (and still do): that part of the balance is kept
-- on the order so the derived balance never forgets it.
ALTER TABLE public.purchase_order ADD COLUMN unallocated_accrued_minor bigint NOT NULL DEFAULT 0;

-- Backfill: received = ordered - open where an open quantity was recorded; its accrual at the line's unit price.
UPDATE public.purchase_order_line
   SET received_quantity = GREATEST(0, quantity_decimal - open_quantity_decimal),
       received_accrued_minor = CASE
           WHEN quantity_decimal > 0
               THEN ROUND(line_total_minor * GREATEST(0, quantity_decimal - open_quantity_decimal) / quantity_decimal)
           ELSE 0 END
 WHERE open_quantity_decimal IS NOT NULL
   AND open_quantity_decimal < quantity_decimal;
