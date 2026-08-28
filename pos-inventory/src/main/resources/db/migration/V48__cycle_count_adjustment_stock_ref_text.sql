-- The adjustment's stock reference is the same key the inventory ledger
-- aggregates by (inventory_ledger_entry.stock_item_id, freeform text: a SKU
-- code such as 'OIL-5W30-5QT', or a catalog product UUID rendered as text —
-- SKU codes and product/catalog ids are different things). Typing this column
-- uuid dead-ended the count → adjustment leg for every non-UUID SKU: no UUID
-- could ever match those ledger rows, so their variances could never be
-- adjusted or posted. Widened to the ledger column's own type; existing UUID
-- values keep working verbatim as their text rendering.
ALTER TABLE cycle_count_adjustment
    ALTER COLUMN stock_item_id TYPE character varying(255) USING stock_item_id::text;
