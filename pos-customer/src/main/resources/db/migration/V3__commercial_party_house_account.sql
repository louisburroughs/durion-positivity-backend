-- CAP:550 S7 (#2505; accounting workspace spec section 4.4 item 2, decisions AW12 and AW13):
-- the per-tenant CASH house account. A walk-in sale paid in full is recorded against this one
-- system party, so no module ever has to invent a person for it.
--
-- The marker is NULL for every ordinary party. Only pos-customer's HouseAccountProvisioner writes
-- it; no request, bulk-ingest record or command can.
ALTER TABLE commercial_party ADD COLUMN house_account varchar(20);

ALTER TABLE commercial_party
    ADD CONSTRAINT commercial_party_house_account_chk
    CHECK (house_account IS NULL OR house_account = 'CASH_SALE');

-- At most one house account of each kind per tenant. The index leads with tenant_id
-- (TENANCY_SCHEMA): it is the arbiter when two instances provision the same tenant at once.
CREATE UNIQUE INDEX commercial_party_house_account_uk
    ON commercial_party (tenant_id, house_account)
    WHERE house_account IS NOT NULL;

COMMENT ON COLUMN commercial_party.house_account IS
    'System house-account kind (CASH_SALE = the tenant''s walk-in CASH account); NULL for ordinary parties. Written only by the provisioner.';
