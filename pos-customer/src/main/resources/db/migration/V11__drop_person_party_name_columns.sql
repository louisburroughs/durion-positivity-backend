-- V11: Drop person_party name columns (issue #684, thin-link drop Step 4).
--
-- pos-people is the source of truth for person identity (ADR-0015 I2). Names
-- are read from pos-people via the canonical person_id link; the local
-- first_name/last_name copy was a transition-period fallback and is now removed.
-- Irreversible — a pos-people outage degrades name reads (no local copy), an
-- accepted trade-off per the issue gate.
--
-- primary_address is intentionally retained: it is declared on the shared
-- AbstractParty base (TABLE_PER_CLASS), so the column also backs commercial_party
-- and is out of scope for this drop.

ALTER TABLE person_party DROP COLUMN IF EXISTS first_name;
ALTER TABLE person_party DROP COLUMN IF EXISTS last_name;
