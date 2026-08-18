-- CAP #1315 Phase 2: record what pos-inventory actually decided about each checked-out line.
--
-- Checkout labels a line from the availability replica, which lags the owning ledger by normal
-- Kafka latency. That label is provisional: the authoritative answer arrives seconds later as an
-- inventory.reservation.outcome.recorded fact, and it can disagree — a line labelled AVAILABLE
-- from a stale replica can come back uncovered, and the reverse.
--
-- These two columns hold the authoritative result so the disagreement is visible rather than
-- silently overwritten. Without them a line flipped to BACKORDER by the outcome would carry no
-- trace of which backorder covers it, leaving the order unable to answer "when do I get this?"
-- even though pos-inventory knows.
--
-- Both are pos-inventory-owned identifiers stored for reference only; nothing here joins to them
-- (no cross-service foreign keys) and nothing in this module mints them.

ALTER TABLE sales_order_line ADD COLUMN reservation_id UUID;
ALTER TABLE sales_order_line ADD COLUMN backorder_id UUID;

-- Resolving an outcome fact is a lookup by the line the fact names.
CREATE INDEX IF NOT EXISTS idx_sales_order_line_reservation_id
    ON sales_order_line (reservation_id);
