-- The tenant's locale (BCP 47, ADR-0030 §2, #2618) rides on the tenant projection pos-tenant
-- publishes. Nullable: a replica row written before the field existed holds no locale until the
-- next fact for that tenant carries one.
--
-- ext_tenant is a global table (no tenant_id, no policy), like the rest of this replica.

ALTER TABLE public.ext_tenant
    ADD COLUMN locale character varying(35);
