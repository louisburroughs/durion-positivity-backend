-- CAP:550 S9 (#2507): the customer-party replica carries the owner's house-account kind
-- (CustomerPartyUpdatedV1.houseAccount, S7), so revenue-by-customer can leave the tenant's CASH
-- (walk-in) account out of the per-customer ranking from the flag alone and never from a name or
-- a customer number. NULL for every ordinary party and for rows replicated before pos-customer
-- published the field; a party-fact replay fills existing rows. Tenant-scoped replica; RLS unchanged.
ALTER TABLE public.ext_customer_party
    ADD COLUMN house_account character varying(20);

COMMENT ON COLUMN public.ext_customer_party.house_account IS
    'CAP:550 S9: owner-published house-account kind (CASH_SALE for the tenant CASH account); NULL for ordinary parties.';
