-- CAP-325 (spec D14.1 consequence, ruled on durion#482): the service_location_capabilities
-- registry is retired. Absence from the specialty map is a definite "general work", so a
-- location-owned vocabulary of capabilities is no longer load-bearing for eligibility; bays
-- already claim catalog operation codes (V3/V4), and this migration moves the one remaining
-- consumer -- mobile units -- onto the same vocabulary.
--
-- mobile_unit_capabilities held registry ids. Its rows cannot be carried across honestly: a
-- registry capability (BRAKE_SERVICE) is a coarser thing than an operation code
-- (BRAKE-PAD-REPLACE-FRONT), and inventing the mapping here would assert equipment nobody
-- declared. Pre-production, the alpha seeder re-declares every unit's codes from
-- location/mobile-units.csv; a unit that predates this migration reads as claiming nothing
-- until it is PATCHed, which the API now allows.
CREATE TABLE public.mobile_unit_service_capability_codes (
    tenant_id uuid DEFAULT public.app_current_tenant() NOT NULL,
    mobile_unit_id uuid NOT NULL,
    operation_code character varying(64) NOT NULL,
    CONSTRAINT mobile_unit_service_capability_codes_pkey PRIMARY KEY (tenant_id, mobile_unit_id, operation_code),
    CONSTRAINT mobile_unit_service_capability_codes_unit_fkey
        FOREIGN KEY (tenant_id, mobile_unit_id) REFERENCES public.mobile_units(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX mobile_unit_service_capability_codes_tenant_idx
    ON public.mobile_unit_service_capability_codes USING btree (tenant_id);

ALTER TABLE public.mobile_unit_service_capability_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mobile_unit_service_capability_codes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public.mobile_unit_service_capability_codes
    USING (tenant_id = public.app_current_tenant())
    WITH CHECK (tenant_id = public.app_current_tenant());

DROP TABLE public.mobile_unit_capabilities;
DROP TABLE public.service_location_capabilities;
