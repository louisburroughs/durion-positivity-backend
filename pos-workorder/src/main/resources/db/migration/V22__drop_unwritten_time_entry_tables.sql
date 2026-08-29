-- Retire pos-workorder's time_entry surface (#1564).
--
-- Nothing in this module ever inserted into either table. TimeEntryServiceImpl only
-- read and updated, the two endpoints it backed required status SUBMITTED and so could
-- only ever answer 404, and DRAFT/SUBMITTED were unreachable states. The adjustment
-- table hung off the same unwritten rows: its repository was never called from any
-- service.
--
-- Technician time is already recorded twice in this module, by writers that do exist:
-- work_session (a mechanic clocked onto a task) and workorder_labor_entry (labor
-- against a service line). Employee time entries belong to pos-people, fed by its clock
-- surface, and workorder labor time already reaches pos-people as
-- workorder.job-time.recorded.v1.
--
-- Dropped child-first: time_entry_adjustment carries an FK to time_entry.

DROP TABLE IF EXISTS time_entry_adjustment;
DROP TABLE IF EXISTS time_entry;
