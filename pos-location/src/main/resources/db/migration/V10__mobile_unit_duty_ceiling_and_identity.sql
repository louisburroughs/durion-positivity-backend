-- #2267, DECISION-LOCATION-029: mobile units gain the same duty-class ceiling axis as bays
-- (spec D13, V8 on bays) plus a small, optional, display-only identity. Neither is equipment: no
-- equipment list, usual crew or hours are added (spec D14.2; crew is People's, hours follow the
-- unit's base location per DECISION-SHOPMGMT-023).
--
-- max_duty_class is the same GVWR class ceiling as bays.max_duty_class (V8): 1..8, null meaning
-- unconstrained. The alpha vans claim FLEET-PM-* work, which needs a duty ceiling to be scheduled
-- safely once #2269 checks it at placement.
--
-- unit_number, vin, license_plate and plate_region are optional and never read by scheduling
-- (MobileUnitRequest/@Schema says so). Each is unique per tenant only while set: a partial unique
-- index (WHERE ... IS NOT NULL) lets any number of units leave a field blank without colliding on
-- null, the same shape as every other optional-but-unique column on this table. license_plate and
-- plate_region are a pair -- a plate number alone repeats across states/regions -- so their index
-- covers both columns and only fires once both are set; a plate recorded without its region (or
-- vice versa) is not checked for a duplicate.
ALTER TABLE public.mobile_units ADD COLUMN max_duty_class integer;
ALTER TABLE public.mobile_units
    ADD CONSTRAINT mobile_units_max_duty_class_check
    CHECK (max_duty_class IS NULL OR max_duty_class BETWEEN 1 AND 8);

ALTER TABLE public.mobile_units ADD COLUMN unit_number character varying(32);
ALTER TABLE public.mobile_units ADD COLUMN vin character varying(17);
ALTER TABLE public.mobile_units ADD COLUMN license_plate character varying(16);
ALTER TABLE public.mobile_units ADD COLUMN plate_region character varying(6);

CREATE UNIQUE INDEX uq_mobile_units_tenant_unit_number
    ON public.mobile_units USING btree (tenant_id, unit_number)
    WHERE unit_number IS NOT NULL;

CREATE UNIQUE INDEX uq_mobile_units_tenant_vin
    ON public.mobile_units USING btree (tenant_id, vin)
    WHERE vin IS NOT NULL;

CREATE UNIQUE INDEX uq_mobile_units_tenant_license_plate
    ON public.mobile_units USING btree (tenant_id, license_plate, plate_region)
    WHERE license_plate IS NOT NULL AND plate_region IS NOT NULL;
