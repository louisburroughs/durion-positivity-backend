-- CAP-323 (#1346): whether a party's work needs fleet payment authorization before it may start.
--
-- A policy of the payer, not of the job. The same commercial account gives the same answer for every
-- workorder it pays for, which is why it sits beside purchase_order_required rather than being
-- decided per workorder — a per-job flag is one somebody forgets to set, and an unset gate is an
-- open gate.
--
-- Defaults false: every party that exists today is unaffected, and no workorder starts blocking
-- because this column appeared.

ALTER TABLE billing_rules
    ADD COLUMN IF NOT EXISTS fleet_authorization_required BOOLEAN NOT NULL DEFAULT FALSE;

-- Which fleet program authorises for this party, as a supplier profile alias.
--
-- Stored with the flag because the two are useless apart: knowing that authorization is required
-- without knowing who to ask leaves every one of this party's workorders blocked with nowhere to go.
ALTER TABLE billing_rules
    ADD COLUMN IF NOT EXISTS fleet_supplier_ref VARCHAR(100);

-- Enforced in the database as well as in the event payload, because a row edited by hand or by a
-- future admin endpoint must not be able to create the unblockable state described above.
ALTER TABLE billing_rules
    ADD CONSTRAINT ck_billing_rules_fleet_ref
    CHECK (fleet_authorization_required = FALSE OR fleet_supplier_ref IS NOT NULL);
