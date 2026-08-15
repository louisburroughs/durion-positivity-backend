-- V34: drop purchase_order.encumbrance_ref — the encumbrance stub is removed (CAP-320)
--
-- Encumbrance accounting reserves committed-but-unspent budget when a purchase order is approved,
-- so the same money cannot be promised twice between approval and invoice. That is a real practice
-- and Durion may want it one day. What existed here was not it.
--
-- The column held a string derived from the purchase order's own id ("ENC-" + first 8 hex chars),
-- stamped only when pos.inventory.encumbranceEnabled was true — and it defaults to false, so no
-- deployment that did not deliberately switch it on has ever written a value. The event published
-- alongside it was an in-process Spring ApplicationEvent that nothing subscribed to, and
-- pos-accounting has no encumbrance concept in code or schema. Nothing read the column back except
-- the response DTO that echoed it.
--
-- Keeping a self-referential identifier and an unlistened event through the CAP-320 purchase-order
-- split would have carried a decision nobody has made into the module that owns commercial orders.
-- Real encumbrance is an event contract to pos-accounting under ADR-0044, not a column here.

ALTER TABLE purchase_order DROP COLUMN IF EXISTS encumbrance_ref;
