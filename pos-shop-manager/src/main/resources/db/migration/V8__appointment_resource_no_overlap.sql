-- CAP-326 (durion#483, spec D17): BAY_DOUBLE_BOOKED is enforced by the database, not by a
-- read-then-write check. Two advisors booking the same bay and an overlapping window concurrently
-- are two INSERTs of two new rows -- neither reads the other's, so no optimistic version ever
-- compares (TOCTOU, not lost-update) -- and only a constraint sees both. Exactly one commits; the
-- other is refused with SQLSTATE 23P01, which AppointmentsServiceImpl translates into a
-- scheduling_conflict row and the DECISION-SHOPMGMT-002 envelope. Which wins is whichever commits
-- first; no ordering is specified or guaranteed.
--
--   * tenant_id leads, and it is security, not style: constraints are enforced over every row
--     regardless of row-level security, so without it a tenant's insert could be refused by -- and
--     so learn of -- another tenant's invisible booking. Precedent: pos-people's
--     ex_time_period_tenant_no_overlap.
--   * Keyed on resource_id, never resource_type: appointment.resource_type is never populated, so a
--     type-predicated constraint would be silently dead.
--   * Partial on the statuses under which an appointment still holds its bay. Cancellation is a
--     status flip, so a cancelled appointment would otherwise hold its slot forever. The list
--     mirrors AppointmentStatus.holdingAResource(); change both together.
--   * '[)' -- back-to-back windows (09:00-10:00, 10:00-11:00) do not conflict.
--   * A reschedule is an UPDATE of start_at/end_at, which PostgreSQL checks identically, so the
--     reschedule path is covered for free and never needs its own check to be correct.
--
-- Building this over a database that already holds overlapping rows fails with 23P01; find them
-- with a self-join on the same predicate before deploying to such a database. Alpha carries no
-- appointment fixtures.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

ALTER TABLE public.appointment
    ADD CONSTRAINT appointment_resource_no_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        resource_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    )
    WHERE (
        resource_id IS NOT NULL
        AND resource_id <> 'UNASSIGNED'
        AND status IN ('SCHEDULED', 'CHECKED_IN', 'WORK_IN_PROGRESS', 'WAITING_FOR_PARTS',
                       'QUALITY_CHECK', 'READY_FOR_PICKUP', 'REOPENED')
    );

COMMENT ON CONSTRAINT appointment_resource_no_overlap ON public.appointment IS
    'CAP-326: one booking per bay per instant. Refused with 23P01; the service records the refusal as a BAY_DOUBLE_BOOKED scheduling_conflict.';
