-- Story C4 (issue #936): concurrency hardening on receivable_payment.
-- Adds the optimistic-lock @Version column so concurrent updates to
-- unapplied_amount conflict at commit instead of silently overwriting
-- each other (last-writer-wins would allow unapplied_amount to go negative).
ALTER TABLE receivable_payment
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
