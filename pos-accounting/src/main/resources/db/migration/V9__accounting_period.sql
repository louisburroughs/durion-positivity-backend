-- Story B1 (issue #937): AccountingPeriod entity + close/reopen service.
--
-- Monthly accounting periods (AD-012: posting only in open periods).
-- Two-state lifecycle per decision D-7: OPEN -> CLOSED, reopenable with
-- mandatory justification. A missing period row counts as OPEN; rows are
-- auto-provisioned by the service on first posting into a nonexistent period.
--
-- Backfill: one OPEN period row per distinct month present in
-- journal_entry.transaction_date, so every month with historical activity has
-- an explicit row that the controller role can close.
--
-- Note on IDs: application-generated primary keys are UUID v7 (ADR-0013).
-- There is no SQL-side UUID v7 helper in this schema, so the backfill uses
-- gen_random_uuid() (PostgreSQL 16 built-in); backfilled rows are one-time
-- inserts and never re-generated.

CREATE TABLE accounting_period (
    period_id uuid NOT NULL,
    period_code character varying(7) NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status character varying(10) NOT NULL,
    closed_at timestamp(6) with time zone,
    closed_by character varying(50),
    reopened_at timestamp(6) with time zone,
    reopened_by character varying(50),
    reopen_justification character varying(1000),
    created_at timestamp(6) with time zone NOT NULL,
    created_by character varying(50) NOT NULL,
    modified_at timestamp(6) with time zone NOT NULL,
    modified_by character varying(50) NOT NULL,
    CONSTRAINT accounting_period_pkey PRIMARY KEY (period_id),
    CONSTRAINT uq_accounting_period_code UNIQUE (period_code),
    CONSTRAINT accounting_period_status_check
        CHECK ((status)::text = ANY ((ARRAY['OPEN'::character varying, 'CLOSED'::character varying])::text[])),
    CONSTRAINT accounting_period_date_check CHECK (start_date <= end_date)
);

CREATE INDEX idx_accounting_period_status ON accounting_period (status);

-- Backfill one OPEN period per distinct month with journal-entry activity.
INSERT INTO accounting_period (
    period_id, period_code, start_date, end_date, status,
    created_at, created_by, modified_at, modified_by)
SELECT
    gen_random_uuid(),
    to_char(m.month_start, 'YYYY-MM'),
    m.month_start::date,
    (m.month_start + interval '1 month' - interval '1 day')::date,
    'OPEN',
    now(),
    'SYSTEM',
    now(),
    'SYSTEM'
FROM (
    SELECT DISTINCT date_trunc('month', transaction_date) AS month_start
    FROM journal_entry
) m
ON CONFLICT (period_code) DO NOTHING;
