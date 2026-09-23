-- #2173: staffing roles are matched as a canonical code (trimmed, upper-cased). pos-people now
-- stores and publishes them that way and PeopleEventsListener canonicalizes on ingest, but replica
-- rows written before either change keep whatever casing arrived. PeopleEventsListener's
-- hasActiveTechnicianAssignment, TechnicianPersonServiceImpl and the location roster query match
-- 'TECHNICIAN' exactly, so a lower-cased replica row would stay invisible to them. Rewrite those
-- rows once. The replica has no uniqueness on role, so no collision handling is needed.
--
-- A person whose only technician assignment arrived non-canonical never got a mechanic row (the
-- listener skipped it). This migration does not synthesize one: the next staffing event for that
-- person, which any save through pos-people's API produces, now creates it.
--
-- ext_people_staffing_assignment is under FORCE ROW LEVEL SECURITY and Flyway connects as the
-- owner with a transitional default tenant binding, so FORCE is lifted for this transaction to
-- reach every tenant's rows, and restored before commit (as pos-people V8/V9 do).
ALTER TABLE public.ext_people_staffing_assignment NO FORCE ROW LEVEL SECURITY;

UPDATE public.ext_people_staffing_assignment
   SET role = upper(btrim(role))
 WHERE role IS NOT NULL
   AND role <> upper(btrim(role));

ALTER TABLE public.ext_people_staffing_assignment FORCE ROW LEVEL SECURITY;
