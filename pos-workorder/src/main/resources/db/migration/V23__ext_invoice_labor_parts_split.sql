-- Wave 2 E5 (#1593): labor/parts split on the ext_invoice replica.
--
-- Derived in the consumer (InvoiceEventsListener) from InvoiceUpdatedV1.lines, summing amount
-- grouped by itemType ("LABOR" / "PART", case-insensitive). Nullable: null means the source event
-- carried no line detail (payload.lines() == null, e.g. an event from a producer that predates
-- #924) and the split is genuinely unknown; zero means the event's lines were authoritatively
-- empty or contained no lines of that type. Consumers of laborRevenue/partsTotal must treat null
-- as "exclude from aggregation", never coerce it to zero.
ALTER TABLE ext_invoice ADD COLUMN labor_total numeric(19, 4);
ALTER TABLE ext_invoice ADD COLUMN parts_total numeric(19, 4);
