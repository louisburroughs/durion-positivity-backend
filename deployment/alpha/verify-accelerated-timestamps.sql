-- Post-run verification for an accelerated alpha seeding run.
--
-- Run against each service schema after the seeder finishes and the stack has been
-- redeployed without the accelerated profile:
--
--   psql -v virtual_start=2025-08-20T12:00:00Z -f verify-accelerated-timestamps.sql
--
-- Pass the anchor as the raw ISO value: the script quotes it itself (:'virtual_start'),
-- so a pre-quoted value would embed apostrophes in the timestamp and fail the cast.
--
-- Every row it returns is a defect. An empty result means every audited timestamp
-- written during the run came from the application clock and landed inside
-- [virtual_start, now()].
--
-- Three checks, one query each:
--   1. future        - created_at or updated_at after wall time. The converging clock
--                      never passes wall time, so this can only come from a write that
--                      bypassed the injected Clock.
--   2. before_anchor - a timestamp earlier than the run's virtual start. Legitimate for
--                      rows that predate the run, so it is reported for review rather
--                      than treated as a hard failure.
--   3. inverted      - updated_at earlier than its own created_at, which no clock,
--                      accelerated or not, can produce.
--
-- ---------------------------------------------------------------------------
-- Residual database-clock reads, accepted deliberately (plan task 5, step 8)
-- ---------------------------------------------------------------------------
-- These stay on wall time and are safe only because virtual time trails it:
--
--   * DEFAULT NOW() columns in pos-security-service, pos-bulk-loader, pos-mcp-server
--     and pos-catalog. The default fires only when an INSERT omits the column, which
--     Hibernate never does for a mapped field.
--   * JPQL CURRENT_TIMESTAMP / CURRENT_DATE comparisons in GLMappingRepository and
--     RoleAssignmentRepository. These are read-side effective-date filters; with virtual
--     time in the past they widen the active set rather than hiding rows.
--   * supplier_schedule_lease.leased_until, last_heartbeat_at and last_run_started_at.
--     Leasing is a real-time liveness concern and must not accelerate; only that table's
--     updated_at is bound from the application clock. The columns are excluded below.
--   * Kafka record timestamps and log timestamps, which are transport metadata.

\set ON_ERROR_STOP on

\if :{?virtual_start}
\else
\echo 'ERROR: pass the run anchor as a raw ISO value, e.g. -v virtual_start=2025-08-20T12:00:00Z'
\quit 1
\endif

WITH audited AS (
    SELECT c.table_schema,
           c.table_name,
           MAX(CASE WHEN c.column_name = 'created_at' THEN 1 ELSE 0 END) AS has_created,
           MAX(CASE WHEN c.column_name = 'updated_at' THEN 1 ELSE 0 END) AS has_updated
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema
     AND t.table_name = c.table_name
     AND t.table_type = 'BASE TABLE'
    WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
      AND c.column_name IN ('created_at', 'updated_at')
      -- Real-time liveness columns live on this table; its updated_at is still audited.
      AND c.table_name <> 'flyway_schema_history'
    GROUP BY c.table_schema, c.table_name
)
SELECT format(
    $q$
    SELECT %L AS check_name,
           %L AS table_name,
           count(*) AS offending_rows
    FROM %I.%I
    WHERE %s
    HAVING count(*) > 0
    $q$,
    check_name,
    table_schema || '.' || table_name,
    table_schema,
    table_name,
    predicate
) AS verification_sql
FROM (
    SELECT a.table_schema,
           a.table_name,
           v.check_name,
           v.predicate
    FROM audited a
    CROSS JOIN LATERAL (
        VALUES
            ('future',
             CASE WHEN a.has_created = 1 AND a.has_updated = 1
                  THEN 'created_at > now() OR updated_at > now()'
                  WHEN a.has_created = 1 THEN 'created_at > now()'
                  ELSE 'updated_at > now()' END),
            ('before_anchor',
             CASE WHEN a.has_created = 1
                  THEN format('created_at < %L::timestamptz', :'virtual_start')
                  ELSE format('updated_at < %L::timestamptz', :'virtual_start') END),
            ('inverted',
             CASE WHEN a.has_created = 1 AND a.has_updated = 1
                  THEN 'updated_at < created_at'
                  ELSE 'false' END)
    ) AS v(check_name, predicate)
) AS checks
ORDER BY table_schema, table_name, check_name
-- The query above emits one SELECT per (table, check); \gexec runs each of them, which
-- keeps this file schema-agnostic as tables are added. It terminates the generator query,
-- so no semicolon before it.
\gexec

-- Spot-check the modules that bypassed auditing before this plan, since they are the
-- ones whose timestamps were most recently repaired:
--
--   SELECT 'tax_exemption' AS source, min(created_at), max(created_at) FROM exemption_certificate
--   UNION ALL SELECT 'tax_provider_txn', min(created_at), max(created_at) FROM tax_provider_transaction
--   UNION ALL SELECT 'price_promotion', min(created_at), max(created_at) FROM promotion_offer
--   UNION ALL SELECT 'price_restriction', min(created_at), max(created_at) FROM restriction_rule
--   UNION ALL SELECT 'supplier_lease', min(updated_at), max(updated_at) FROM supplier_schedule_lease;
--
-- Expected: the newest value at or before now(), the oldest at or after the run anchor,
-- and the span covering the seeded year.
