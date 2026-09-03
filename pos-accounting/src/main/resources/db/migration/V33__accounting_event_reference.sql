-- accounting-events UI reference number (issue #1680).
--
-- Adds accounting_event.event_reference: a short human-readable label
-- (AE-{YYYYMM}-{n}, e.g. AE-202609-42) so the accounting-events UI pages
-- (/app/accounting/events, /app/accounting/events/:id,
-- /app/accounting/events/failed) and the CRM integration-events page, which
-- consumes this same API, stop rendering the raw eventId UUID. eventId
-- stays the canonical identifier (ADR-0027); event_reference is purely an
-- additional display field, assigned going forward by
-- EventIngestionServiceImpl.submitEvent from the per-month
-- accounting_sequence counter (scope AE-{YYYYMM}, reusing the story A2 /
-- issue #942 numbering machinery — see V13__je_entry_number_sequence.sql).
--
-- Column length: "AE-" + 6-digit YYYYMM + "-" is a fixed 10-character
-- prefix, leaving 10 digits of headroom in varchar(20) for the counter
-- suffix (up to ~9.9 billion events in a single month) — mirrors
-- journal_entry.entry_number's varchar(20) sizing and cannot overflow it
-- under any realistic volume.
--
-- Unlike V13 (journal_entry.entry_number), which deliberately skipped
-- backfill because JE numbering only ever mattered going forward, THIS
-- migration DOES backfill existing rows: the whole point of issue #1680 is
-- that already-ingested events stop showing UUIDs in the UI immediately,
-- not just for events ingested after deployment. Existing rows are
-- numbered AE-{YYYYMM}-{n} by received_at ascending within each month
-- (event_id is only a deterministic tiebreaker for equal received_at
-- timestamps). The matching accounting_sequence rows are then seeded /
-- advanced so post-migration assignment cannot collide with a backfilled
-- reference. The column stays nullable — assignment is a service-layer
-- concern, not a database invariant, and rows can predate this migration
-- transiently while it runs.
--
-- received_at is timestamptz and to_char() would otherwise format it in the
-- session's TimeZone GUC, which on an incremental deploy comes from server /
-- role defaults (V1's `SET TIME ZONE 'UTC'` only bound V1's own session). The
-- explicit `AT TIME ZONE 'UTC'` pins the month bucket to UTC so it matches
-- EventIngestionServiceImpl.eventReferenceScopeKey's receivedAt.atZone(UTC);
-- without it, an event received at e.g. 2026-10-01T00:30Z backfills as
-- AE-202609 under a US session timezone while a same-instant event ingested
-- after deploy would be numbered AE-202610.
--
-- PostgreSQL-only syntax (window functions, regex substring, ON CONFLICT):
-- this module's test suite runs Flyway-generated schema only through
-- Testcontainers PostgreSQL migration ITs (see AccountingPeriodBackfillIT);
-- the H2 unit/contract-IT profile disables Flyway entirely
-- (application-test.yml sets spring.flyway.enabled: false and builds the
-- schema from JPA entities via ddl-auto: create-drop), so no migration
-- script in this module needs to be H2-compatible.

ALTER TABLE accounting_event
    ADD COLUMN event_reference character varying(20);

WITH numbered AS (
    SELECT
        event_id,
        'AE-' || to_char(received_at AT TIME ZONE 'UTC', 'YYYYMM') || '-'
            || row_number() OVER (
                PARTITION BY to_char(received_at AT TIME ZONE 'UTC', 'YYYYMM')
                ORDER BY received_at ASC, event_id ASC
            ) AS reference
    FROM accounting_event
)
UPDATE accounting_event ae
SET event_reference = numbered.reference
FROM numbered
WHERE ae.event_id = numbered.event_id;

ALTER TABLE accounting_event
    ADD CONSTRAINT uq_accounting_event_event_reference UNIQUE (event_reference);

-- Seed/advance accounting_sequence for every AE-{YYYYMM} scope touched by
-- the backfill above, parsing next_value back out of the reference strings
-- just written (rather than recomputing the window function) so the seeded
-- counter is provably consistent with what was actually persisted.
-- ON CONFLICT is defensive only: no AE- scope can pre-exist this migration
-- since nothing wrote event_reference before it.
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
        substring(event_reference FROM '^(AE-[0-9]{6})-') AS scope_key,
        max((substring(event_reference FROM '-([0-9]+)$'))::bigint) AS max_n
    FROM accounting_event
    WHERE event_reference IS NOT NULL
    GROUP BY substring(event_reference FROM '^(AE-[0-9]{6})-')
) scopes
ON CONFLICT (scope_key) DO NOTHING;
