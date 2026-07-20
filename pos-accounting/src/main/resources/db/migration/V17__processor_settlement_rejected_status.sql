-- Story F1c (issue #963) PR #977 review — finding 1 / finding 16.
--
-- Add the terminal REJECTED status to processor_settlement. Settlements that
-- cannot be auto-posted without producing an incoherent journal entry (a
-- non-base currency, or a matched gross exceeding the header gross — which
-- would make the batched JE's suspense leg negative, including
-- net-negative-gross payouts) are rejected at ingest and never enqueued for
-- posting. Widen the status CHECK to permit the new value.

ALTER TABLE processor_settlement
    DROP CONSTRAINT processor_settlement_status_ck;

ALTER TABLE processor_settlement
    ADD CONSTRAINT processor_settlement_status_ck
        CHECK (status IN ('RECEIVED', 'POSTED', 'REJECTED'));
