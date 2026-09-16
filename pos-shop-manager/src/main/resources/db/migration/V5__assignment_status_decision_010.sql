-- CAP-326 (durion#483): bring assignment.status to DECISION-SHOPMGMT-010's six members.
--
-- The baseline shipped four -- CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED -- with CONFIRMED
-- standing where the record says ASSIGNED, and no UNASSIGNED or AWAITING_SKILL_FULFILLMENT at all.
-- AWAITING_SKILL_FULFILLMENT is the mechanism by which a rostered-absence
-- (NO_COMPETENT_MECHANIC_ROSTERED, spec D10.1) stops an assignment without a HARD rule, so it is
-- on this capability's critical path rather than housekeeping.
--
-- The rename is a data rewrite, not a shim: nothing may read CONFIRMED after this runs. The column
-- widens because AWAITING_SKILL_FULFILLMENT is 26 characters and the baseline gave it 20. The CHECK
-- stays a CHECK -- this module's baseline has no CREATE TYPE and this migration does not start one.

ALTER TABLE public.assignment DROP CONSTRAINT assignment_status_check;
ALTER TABLE public.assignment ALTER COLUMN status TYPE character varying(32);

UPDATE public.assignment SET status = 'ASSIGNED' WHERE status = 'CONFIRMED';

ALTER TABLE public.assignment
    ADD CONSTRAINT assignment_status_check
    CHECK (status IN ('UNASSIGNED', 'ASSIGNED', 'AWAITING_SKILL_FULFILLMENT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'));

COMMENT ON COLUMN public.assignment.status IS
    'DECISION-SHOPMGMT-010 lifecycle. ASSIGNED replaced the baseline''s CONFIRMED (CAP-326); AWAITING_SKILL_FULFILLMENT parks a booking no competent mechanic at the location can take, and leaves only by re-assignment or cancellation.';
