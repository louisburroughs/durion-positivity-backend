-- Story B2 (issue #944), Wave-1 carry-over: close-race hardening on
-- accounting_period. Adds the optimistic-lock @Version column so concurrent
-- close/reopen of the same period conflicts at commit instead of silently
-- overwriting each other (mirrors V10 on receivable_payment).
ALTER TABLE accounting_period
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
