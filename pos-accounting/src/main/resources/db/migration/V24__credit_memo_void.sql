-- Issue #997 follow-through: implement the POSTED -> VOIDED credit-memo transition with the
-- GL/report symmetry the #997 rule requires. A void is a new economic event in the period it
-- happens: the memo's original posting-period contribution to the T8 liability report is never
-- restated (no retroactive restatement of closed/frozen periods); instead the void posts a
-- reversing journal entry (Dr AR / Cr Revenue + Cr Sales-Tax Payable 2200) dated at void time and
-- the T8 report restores the reversed tax per jurisdiction in the void's period, keeping GL drift
-- at zero on both sides of the transition.
--
-- FLYWAY COLLISION NOTE: numbered V24 as the next contiguous version after V23. Unmerged
-- accounting-plan branches may also claim a V2x on pos-accounting; if they merge first this file
-- must be renumbered on rebase.

ALTER TABLE credit_memo
    ADD COLUMN voided_timestamp timestamp(6) with time zone,
    ADD COLUMN voided_by_user_id character varying(50),
    ADD COLUMN void_reason character varying(1000);

-- The T8 void-period restoration term queries voided credits by when they were voided.
CREATE INDEX idx_credit_memo_voided_timestamp ON credit_memo (voided_timestamp)
    WHERE voided_timestamp IS NOT NULL;
