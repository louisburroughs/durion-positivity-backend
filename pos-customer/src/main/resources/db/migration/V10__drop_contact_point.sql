-- V10: Drop the local contact_point table (issue #684, thin-link drop 2c.3).
--
-- pos-people is the source of truth for person contact points (ADR-0015 I2).
-- pos-customer dual-wrote contacts to pos-people (#681) and read from pos-people
-- with a local contact_point fallback (#683). With pos-people proven stable in
-- alpha, the local fallback is removed: contacts are now sourced solely from
-- pos-people. This is irreversible — a pos-people outage degrades contact reads
-- (no local copy), an accepted trade-off per the issue gate.
--
-- The FK contact_point.person_id -> person_party(customer_id) is dropped with
-- the table.

DROP TABLE IF EXISTS contact_point;
