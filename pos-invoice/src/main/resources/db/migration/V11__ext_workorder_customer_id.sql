-- Party-id backfill for pre-#920 invoices (#921): the workorder replica gains the owning
-- customer party carried by workorder.workorder.updated facts (WorkorderUpdatedV1.customerId)
-- so a scheduled job can patch invoices.customer_id — NULL on invoices created before #920,
-- which therefore never match the warranty candidate-line search — from ext_workorder.
-- H2-compatible: plain ADD COLUMN only.

ALTER TABLE ext_workorder ADD COLUMN customer_id uuid;
