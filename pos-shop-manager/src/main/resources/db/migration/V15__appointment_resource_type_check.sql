-- DECISION-SHOPMGMT-021 / DECISION-SHOPMGMT-003 (durion-positivity-backend#2268): appointment.
-- resource_type has existed since V1 but was never written by AppointmentsServiceImpl. It now
-- carries which axis resource_id names (BAY | MOBILE_UNIT | UNASSIGNED), so every new appointment
-- write is constrained to those three values, matching the CHECK-constraint style already used for
-- status/source_type/cancellation_reason.
--
-- NULL stays allowed: every appointment created before this change has resource_type = NULL (it
-- was never written) and reschedule reads a NULL as UNASSIGNED (no resource checks) rather than
-- failing an old row it cannot classify (DECISION-SHOPMGMT-021: existing appointments are not
-- re-validated). No backfill: pre-production policy is clean code over migration shims, and there
-- is no reliable source to infer a historical resourceType from.
ALTER TABLE public.appointment ADD CONSTRAINT appointment_resource_type_check
    CHECK (resource_type IS NULL OR (resource_type)::text = ANY (ARRAY[
        ('BAY'::character varying)::text,
        ('MOBILE_UNIT'::character varying)::text,
        ('UNASSIGNED'::character varying)::text
    ]));
