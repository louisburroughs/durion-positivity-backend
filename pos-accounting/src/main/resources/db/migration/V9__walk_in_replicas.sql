-- #2508 (CAP:550 S11; SPEC-accounting-workspace §4.4 items 1-4, §9.5a; AW12-AW14): what the unpaid walk-in
-- sales read needs from the owners' replicas.
--
-- 1. ext_customer_party.house_account: CustomerPartyUpdatedV1.houseAccount (#2505, S7). CASH_SALE marks the
--    tenant's CASH (walk-in) house account; null for every other party. Accounting keys walk-in rules on this
--    flag only, never on the customer number or name.
-- 2. ext_location.timezone: LocationUpdatedV1.timezone (IANA id). A walk-in sale's business day is read in its
--    location's time zone; null falls back to UTC.
--
-- Both are nullable and stay null until the owner's next fact for the row: existing rows fill when pos-customer
-- and pos-location replay their facts (equal versions apply, ReplicaVersionGuard). No backfill (AW13). Both
-- tables keep their tenancy schema (tenant_id, row-level security, tenant_isolation) unchanged.

ALTER TABLE public.ext_customer_party ADD COLUMN house_account character varying(20);

COMMENT ON COLUMN public.ext_customer_party.house_account IS
    'House-account kind from CustomerPartyUpdatedV1.houseAccount (#2505/#2508): CASH_SALE for the tenant''s CASH'
    ' walk-in account, null for every other party.';

ALTER TABLE public.ext_location ADD COLUMN timezone character varying(64);

COMMENT ON COLUMN public.ext_location.timezone IS
    'IANA time zone from LocationUpdatedV1.timezone (#2508): where a walk-in sale''s business day ends; null reads'
    ' as UTC.';
