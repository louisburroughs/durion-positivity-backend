-- #1623: replicate deposit-take provenance onto the invoice replica.
-- A deposit-take invoice (the document a deposit-take order renders for the down-payment itself)
-- is a contract liability, not a sale — Accounting's ruling on #1623 — so revenue-shaped measures
-- summing ext_invoice.total (E2 `invoiced`, and through it collectionRatePct) must exclude rows
-- carrying a non-null deposit_source_type. Values mirror pos-invoice's DepositSourceType
-- (ESTIMATE / WORKORDER / ORDER); null on ordinary invoices and on rows replicated from events
-- predating the enrichment (pos-invoice has no replay yet, so pre-existing deposit-take rows
-- stay unmarked until their next invoice.invoice.updated event).

ALTER TABLE ext_invoice ADD COLUMN deposit_source_type VARCHAR(16);
