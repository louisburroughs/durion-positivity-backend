-- Issue #2191 (SPEC-inventory-adjustment-gl-posting §4.9, #2186 decision D5): Kafka-consumed
-- inventory posting facts write one terminal accounting_event ingestion record each. A fact the
-- consumer deliberately does not post (an uncosted adjustment or scrap, failure_reason_code
-- UNCOSTED_FACT) ends in the new terminal status SKIPPED, which is not retryable: the REST retry
-- scheduler and retryAccountingEvent select only FAILED and SUSPENDED. The column is varchar(20),
-- wide enough; only the baseline's CHECK constraint needs the new value.
ALTER TABLE accounting_event DROP CONSTRAINT accounting_event_status_check;

ALTER TABLE accounting_event ADD CONSTRAINT accounting_event_status_check CHECK (
    status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'SUSPENDED', 'SKIPPED'));
