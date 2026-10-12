-- Drawer actor names (#2608): the approving manager's sign-in name, kept where the approval is issued and copied onto
-- the movement that uses it, so a read can resolve it to a display name through the people-contact copy (V11). The
-- approver's user id (approved_by, approver_user_id) stays the identity of record; the username is only a lookup key.
-- It is the name the step-up verified, exactly as checked. Rows written before this migration keep null, and their
-- approvedByName is served null. Additive and nullable: no backfill, no lock beyond the catalogue change.

ALTER TABLE public.cash_movement_approval ADD COLUMN approver_username character varying(255);
ALTER TABLE public.cash_movement ADD COLUMN approved_by_username character varying(255);
