-- Index the two "currently open" lookups the work-session reads depend on (#2061 follow-up).
--
-- The flattened baseline gives work_session and work_session_break one index each, on tenant_id
-- alone, plus their primary keys and the (tenant_id, <pk>) tenant keys. Nothing indexes person_id,
-- session_id, ended_at or started_at. Every "is this person clocked in" lookup therefore scans the
-- tenant's whole session history:
--
--   WorkSessionRepository.findByPersonIdAndEndedAtIsNull                      (start/stop, always)
--   WorkSessionRepository.findByPersonIdInAndEndedAtIsNullOrderByStartedAtDesc (#2061, per board read)
--   WorkSessionBreakRepository.findBySession_SessionIdAndEndedAtIsNull        (break control)
--   WorkSessionBreakRepository.findBySession_SessionIdInAndEndedAtIsNull      (#2061, per board read)
--
-- The gap predates #2061 — the single-person start/stop queries have always scanned — but #2061 put
-- the batched pair on the daily dispatch board, which refreshes continuously, so an occasional scan
-- became a constant one over a table that grows by roughly one row per person per day forever.
-- #2061's BR7 asked for a bounded number of queries and got it; this bounds what each one reads.
--
-- Both are partial on the open rows. That is what keeps them cheap: at most one session is open per
-- person and at most one break is open per session, so each index stays proportional to the number
-- of people currently clocked in rather than to all history ever recorded. Rows gain an entry when
-- a session or break opens and lose it when one closes, which is also the only write path that
-- touches them.
--
-- Tenant-leading, per docs/TENANCY_SCHEMA.md: the row-level-security predicate filters on tenant_id
-- first, so an index that does not lead with it cannot serve the query.
--
-- Deliberately NOT unique. A unique partial index here would additionally close #2061's BR2 gap —
-- work_session has no uniqueness on (person, open), so two concurrent starts can both commit past
-- startWorkSession's pre-check, and the DataIntegrityViolationException it catches has nothing to
-- raise it. The repository has precedent for that shape (uq_invoice_gl_posting_open,
-- idx_bulk_load_job_one_active_per_operator). It is left out here because it is a write-behaviour
-- change, not an index: it would make a racing start fail with 409 instead of succeeding, and it
-- would refuse to apply at all against any environment that already holds a duplicate. That is a
-- decision for its own change with its own data check.
CREATE INDEX work_session_open_by_person_idx
    ON public.work_session USING btree (tenant_id, person_id)
    WHERE (ended_at IS NULL);

CREATE INDEX work_session_break_open_by_session_idx
    ON public.work_session_break USING btree (tenant_id, session_id)
    WHERE (ended_at IS NULL);

COMMENT ON INDEX public.work_session_open_by_person_idx IS
    'Open session per person: serves findByPersonIdAndEndedAtIsNull and the batched IN form the dispatch board reads (#2061). Partial, so it holds only people currently clocked in.';

COMMENT ON INDEX public.work_session_break_open_by_session_idx IS
    'Open break per session: serves findBySession_SessionIdAndEndedAtIsNull and its batched IN form (#2061). Partial, so it holds only breaks currently running.';
