-- DECISION-SHOPMGMT-004 / DECISION-SHOPMGMT-022 (durion-positivity-backend#2270): the reschedule
-- allowance (up to 2 free reschedules; the 3rd and later need appointments:reschedule:approve and
-- a non-blank approvalReason) and the shop-caused exemption, plus the resource axis a reschedule
-- moved from/to.
--
-- counts_against_allowance defaults true so every reschedule recorded before this change, and any
-- ordinary customer-caused one going forward, counts toward the threshold. AppointmentsServiceImpl
-- sets it false only for a reschedule the shop caused: reason EQUIPMENT_ISSUE, or the appointment
-- was already DECISION-SHOPMGMT-022 "affected" at the moment of the reschedule, evaluated before
-- any change is applied.
--
-- previous_resource_id / new_resource_id are nullable and sized like appointment.resource_id (V1),
-- which they mirror: most reschedules never touch the resource axis, and new_resource_id is
-- populated only when the caller named a newResourceId/newResourceType and the appointment moved
-- onto it (DECISION-SHOPMGMT-022 rule 3).
--
-- approval_reason records why a manager approved a reschedule beyond the allowance; null for every
-- reschedule that needed no approval.
--
-- Pure ADD COLUMN with a default on an existing tenant-scoped table: no RLS bracket needed
-- (../durion/docs/architecture/deployment/TENANCY_SCHEMA.md).
ALTER TABLE public.reschedule_history
    ADD COLUMN counts_against_allowance boolean NOT NULL DEFAULT true,
    ADD COLUMN previous_resource_id character varying(128),
    ADD COLUMN new_resource_id character varying(128),
    ADD COLUMN approval_reason character varying(1000);
