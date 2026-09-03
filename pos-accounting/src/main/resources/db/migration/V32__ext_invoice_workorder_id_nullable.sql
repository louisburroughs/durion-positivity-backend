-- ADR-0044 R3: pos-invoice legitimately produces invoices with no originating
-- workorder (order-fronted/counter sales, standalone billing, deposit-settlement
-- documents) — workorder_id NOT NULL made those invoices unrepresentable in the
-- replica, silently dropping them from A/R aging, collections, and payment-lag
-- analytics (issue #1651). The existing index is kept as-is; a partial index over
-- non-null values is unnecessary at current row counts.
ALTER TABLE ext_invoice ALTER COLUMN workorder_id DROP NOT NULL;
