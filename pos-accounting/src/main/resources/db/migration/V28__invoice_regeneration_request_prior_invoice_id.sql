-- Issue #1537 (F4): an unrelated workorder update (e.g. a note edit) that merely echoes the
-- workorder's PRE-EXISTING invoiceId was falsely resolving a PENDING regeneration request to
-- COMPLETED with that stale id. WorkorderEventsListener now refuses to resolve a row from a fact
-- carrying the same invoiceId the requester already had when the command was published.
ALTER TABLE invoice_regeneration_request
    ADD COLUMN prior_invoice_id uuid;
