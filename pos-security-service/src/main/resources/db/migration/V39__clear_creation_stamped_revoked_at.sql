-- #1910: clear revoked_at values that were stamped at creation rather than by a revocation.
--
-- RoleAssignment.setEffectiveEndDate used to stamp revoked_at on any non-null end date, and
-- createRoleAssignment sets the end date through that same setter. Every assignment created with
-- a bounded window was therefore marked revoked at birth, and revoked_at — which the API
-- publishes on RoleAssignmentDto — read as "has an end date" rather than "was revoked".
--
-- A genuine revocation goes through revokeRoleAssignment or revokeRoleFromUser, both of which
-- also write last_modified_at (as does the entity's @PreUpdate hook on any later update). A row
-- that carries a revoked_at while never having been updated can only have got it from the
-- creation-time setter, so that is the discriminator here. It has no false positives: a real
-- revocation always leaves last_modified_at behind.
--
-- Residue this deliberately leaves alone: an assignment created with a bounded window that was
-- later updated for some other reason keeps its creation-stamped revoked_at, because nothing
-- distinguishes it from a real revocation after the fact. Those rows stay wrong until they are
-- revoked for real.
--
-- No authorization consequence either way. Effective dating reads effective_start_date and
-- effective_end_date; revoked_at is audit metadata and is not consulted when deciding whether an
-- assignment grants anything.
UPDATE role_assignments
SET revoked_at = NULL
WHERE revoked_at IS NOT NULL
  AND last_modified_at IS NULL;
