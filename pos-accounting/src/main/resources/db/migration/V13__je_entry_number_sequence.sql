-- Story A2 (issue #942, decision D-1): posted-entry numbering.
--
-- Per-month counter table backing JE-{YYYYMM}-{seq} entry numbers assigned
-- at POST time inside the posting transaction. One row per scope_key
-- (e.g. 'JE-202607'); next_value is the next number to hand out (1-based).
-- Assignment locks the row (SELECT ... FOR UPDATE) and increments it in the
-- same transaction as the DRAFT -> POSTED status flip, so concurrent posts
-- serialize per month and a posting rollback returns the number — gapless as
-- a side effect of post-time assignment; no statutory guarantee claimed (D-1).
--
-- journal_entry.entry_number is nullable and NOT backfilled: entries posted
-- before this migration stay unnumbered (numbering starts at 1 for each
-- month's first post after deployment). Uniqueness applies to non-null
-- values only (Postgres UNIQUE ignores NULLs).

CREATE TABLE accounting_sequence (
    sequence_id uuid NOT NULL,
    scope_key character varying(20) NOT NULL,
    next_value bigint NOT NULL,
    CONSTRAINT accounting_sequence_pkey PRIMARY KEY (sequence_id),
    CONSTRAINT uq_accounting_sequence_scope_key UNIQUE (scope_key),
    CONSTRAINT accounting_sequence_next_value_check CHECK (next_value >= 1)
);

ALTER TABLE journal_entry
    ADD COLUMN entry_number character varying(20);

ALTER TABLE journal_entry
    ADD CONSTRAINT uq_journal_entry_entry_number UNIQUE (entry_number);
