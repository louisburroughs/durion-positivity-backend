-- Credit-memo UI reference number (issue #1779).
--
-- Adds credit_memo.credit_memo_reference: a short human-readable label
-- (CM-{YYYYMM}-{n}, e.g. CM-202609-7) so credit-memo list and detail screens
-- stop rendering the raw creditMemoId UUID. creditMemoId stays the canonical
-- identifier (ADR-0027); credit_memo_reference is purely an additional
-- display field, assigned going forward by CreditMemoServiceImpl.createCreditMemo
-- from the per-month accounting_sequence counter (scope CM-{YYYYMM}), reusing
-- the same numbering machinery as accounting_event.event_reference
-- (V33, issue #1680) and journal_entry.entry_number (V13, issue #942).
--
-- Column length matches those two: "CM-" + 6-digit YYYYMM + "-" is a fixed
-- 10-character prefix, leaving 10 digits of counter headroom in varchar(20).
--
-- Like V33 (and unlike V13), this migration DOES backfill existing rows: the
-- point of #1779 is that already-issued memos stop showing UUIDs immediately,
-- not just memos issued after deployment. Existing rows are numbered
-- CM-{YYYYMM}-{n} by creation_timestamp ascending within each month
-- (credit_memo_id is only a deterministic tiebreaker for equal timestamps).
-- The matching accounting_sequence rows are then seeded / advanced so
-- post-migration assignment cannot collide with a backfilled reference. The
-- column stays nullable — assignment is a service-layer concern, not a
-- database invariant.
--
-- creation_timestamp is timestamptz and to_char() would otherwise format it in
-- the session's TimeZone GUC, which on an incremental deploy comes from server
-- / role defaults. The explicit `AT TIME ZONE 'UTC'` pins the month bucket to
-- UTC so it matches CreditMemoServiceImpl's creationTimestamp.atZone(UTC);
-- without it, a memo created at e.g. 2026-10-01T00:30Z backfills as CM-202609
-- under a US session timezone while a same-instant memo created after deploy
-- would be numbered CM-202610.
--
-- PostgreSQL-only syntax (window functions, regex substring, ON CONFLICT): this
-- module's test suite runs Flyway-generated schema only through Testcontainers
-- PostgreSQL migration ITs; the H2 unit/contract-IT profile disables Flyway
-- entirely (application-test.yml sets spring.flyway.enabled: false and builds
-- the schema from JPA entities via ddl-auto: create-drop), so no migration
-- script in this module needs to be H2-compatible. See V33's header note.

ALTER TABLE credit_memo
    ADD COLUMN credit_memo_reference character varying(20);

WITH numbered AS (
    SELECT
        credit_memo_id,
        'CM-' || to_char(creation_timestamp AT TIME ZONE 'UTC', 'YYYYMM') || '-'
            || row_number() OVER (
                PARTITION BY to_char(creation_timestamp AT TIME ZONE 'UTC', 'YYYYMM')
                ORDER BY creation_timestamp ASC, credit_memo_id ASC
            ) AS reference
    FROM credit_memo
)
UPDATE credit_memo cm
SET credit_memo_reference = numbered.reference
FROM numbered
WHERE cm.credit_memo_id = numbered.credit_memo_id;

CREATE UNIQUE INDEX uq_credit_memo_reference ON credit_memo (credit_memo_reference);

-- Seed/advance accounting_sequence for every CM-{YYYYMM} scope touched by the
-- backfill above, parsing next_value back out of the reference strings just
-- written (rather than recomputing the window function) so the seeded counter
-- is provably consistent with what was actually persisted. ON CONFLICT is
-- defensive only: no CM- scope can pre-exist this migration since nothing wrote
-- credit_memo_reference before it.
INSERT INTO accounting_sequence (sequence_id, scope_key, next_value)
SELECT
    -- SQL-side UUID v7 (see V9__accounting_period.sql header note for the
    -- recipe): ms-epoch timestamp in bytes 1-6, version bits forced to 7,
    -- variant 10 retained from gen_random_uuid().
    encode(
        set_bit(
            set_bit(
                overlay(uuid_send(gen_random_uuid())
                        placing substring(int8send((extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3 FOR 6)
                        FROM 1 FOR 6),
                52, 1),
            53, 1),
        'hex')::uuid,
    scope_key,
    max_n + 1
FROM (
    SELECT
        substring(credit_memo_reference FROM '^(CM-[0-9]{6})-') AS scope_key,
        max((substring(credit_memo_reference FROM '-([0-9]+)$'))::bigint) AS max_n
    FROM credit_memo
    WHERE credit_memo_reference IS NOT NULL
    GROUP BY substring(credit_memo_reference FROM '^(CM-[0-9]{6})-')
) scopes
ON CONFLICT (scope_key) DO NOTHING;
